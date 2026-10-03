"""
kline.py - K-line (ISO 14230 / KWP2000) core for Kawasaki KDS ECUs.

No GUI code lives here, so this module can be reused by command-line scripts,
tests, or a future phone-app port. The GUI (kawasaki_logger.py) talks to the
Engine thread at the bottom of this file through two queues.

Protocol summary (what this code sends):
  wake-up       ISO 14230 fast init: 25 ms low, 25 ms high on the K-line
  80/81 11 F1   format byte, ECU address (0x11 default), tester address (0xF1)
  81            StartCommunication      -> ECU answers C1 <key bytes>
  10 80         StartDiagnosticSession  -> 50 80   (Kawasaki wants this first)
  21 <pid>      ReadDataByLocalIdentifier -> 61 <pid> <data...>
  3E 01         TesterPresent (keep-alive while idle)
  82            StopCommunication
Every frame ends with an 8-bit additive checksum.
"""
from __future__ import annotations

import csv
import json
import math
import os
import queue
import random
import threading
import time
import traceback
from datetime import datetime

try:
    from serial import SerialException as PortError      # raised when the USB serial port itself fails
except ImportError:
    class PortError(Exception):                           # pyserial missing: only the demo works
        pass

DEMO_PORT = "DEMO"

SID_START_COMM = 0x81
SID_STOP_COMM = 0x82
SID_START_DIAG = 0x10
SID_READ_LOCAL_ID = 0x21
SID_TESTER_PRESENT = 0x3E
NEGATIVE_RESPONSE = 0x7F

NRC_NAMES = {
    0x10: "general reject",
    0x11: "service not supported",
    0x12: "sub-function not supported / invalid format",
    0x21: "busy, repeat request",
    0x22: "conditions not correct",
    0x31: "request out of range",
    0x33: "security access denied",
    0x35: "invalid key",
    0x78: "response pending",
    0x80: "not supported in active session",
}

DEFAULT_SETTINGS = {
    "port": "",
    "ecu_addr": "auto",              # auto tries each address in ECU_AUTO_ADDRESSES
    "tester_addr": "0xF1",
    "init_method": "break",          # "break" or "360baud"
    "baud": 10400,
    "diag_session": "0x80",          # blank = skip StartDiagnosticSession
    "request_gap_ms": 55,            # ISO P3 min; forum reports Kawasaki ignores < ~49 ms
    "response_timeout_ms": 300,
    "fast_switch_cmd": "",           # hex request that tells the ECU to change baud (blank = stay)
    "fast_baud": 62500,
    "dtr": True,
    "rts": False,
    "log_dir": "logs",
    "log_raw_columns": True,
    "run_name": "ski",
    "temp_unit": "F",                # F or C: how temperature channels are displayed and logged
    "advanced": True,                # show PID scanner, console and raw bytes in the GUI
}

ECU_AUTO_ADDRESSES = (0x11, 0x28)   # the two addresses Kawasaki KDS ECUs are known to use


# --------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------

class KLineError(Exception):
    """Anything that went wrong talking to the ECU."""


class NoResponse(KLineError):
    pass


class BadChecksum(KLineError):
    pass


class NegativeResponse(KLineError):
    def __init__(self, sid: int, code: int):
        self.sid, self.code = sid, code
        super().__init__(f"ECU refused service 0x{sid:02X}: {nrc_name(code)} (0x{code:02X})")


class LinkLost(Exception):
    pass


def nrc_name(code: int) -> str:
    return NRC_NAMES.get(code, "unknown reason")


def hexs(data) -> str:
    return " ".join(f"{b:02X}" for b in data) if data else ""


def parse_hex(text: str) -> bytes:
    """'21 09', '0x21,0x09' and '2109' all become b'\\x21\\x09'."""
    cleaned = text.replace(",", " ").replace("0x", " ").replace("0X", " ")
    out = []
    for part in cleaned.split():
        if len(part) % 2:
            part = "0" + part
        for i in range(0, len(part), 2):
            out.append(int(part[i:i + 2], 16))
    return bytes(out)


def to_int(value) -> int:
    """Settings ints: '0x11' is hex, '55' is decimal."""
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    text = str(value).strip()
    return int(text, 16) if text.lower().startswith("0x") else int(text)


def parse_pid(value) -> int:
    """Byte fields (PIDs, addresses, session) are always hex: '09', '0x09' and 9 all mean 0x09."""
    if isinstance(value, int):
        pid = value
    else:
        text = str(value).strip().lower().replace("0x", "")
        pid = int(text, 16)
    if not 0 <= pid <= 0xFF:
        raise ValueError(f"PID {value!r} is outside 0x00-0xFF")
    return pid


def checksum(data) -> int:
    return sum(data) & 0xFF


def build_frame(target: int, source: int, payload, length_in_fmt: bool = False) -> bytes:
    payload = bytes(payload)
    n = len(payload)
    if not 1 <= n <= 255:
        raise ValueError("payload must be 1-255 bytes")
    if length_in_fmt and n <= 63:
        head = bytes([0x80 | n, target, source])
    else:
        head = bytes([0x80, target, source, n])
    body = head + payload
    return body + bytes([checksum(body)])


def frame_length(buf) -> int | None:
    """Total length of the frame starting at buf[0], or None if not known yet."""
    if not buf:
        return None
    fmt = buf[0]
    pos = 3 if fmt & 0xC0 else 1
    n = fmt & 0x3F
    if n == 0:
        if len(buf) <= pos:
            return None
        n = buf[pos]
        pos += 1
    return pos + n + 1


def split_frame(frame: bytes):
    """-> (target, source, payload). target/source are None if the frame has no address bytes."""
    fmt = frame[0]
    pos, tgt, src = 1, None, None
    if fmt & 0xC0:
        tgt, src, pos = frame[1], frame[2], 3
    n = fmt & 0x3F
    if n == 0:
        n = frame[pos]
        pos += 1
    return tgt, src, bytes(frame[pos:pos + n])


def precise_sleep(seconds: float) -> None:
    """time.sleep is too coarse on Windows for the 25 ms wake-up pulse, so finish with a spin."""
    if seconds <= 0:
        return
    end = time.perf_counter() + seconds
    if seconds > 0.03:
        time.sleep(seconds - 0.02)
    while time.perf_counter() < end:
        pass


def serial_available() -> bool:
    try:
        import serial  # noqa: F401
        return True
    except ImportError:
        return False


def fmt_num(value) -> str:
    if value is None:
        return ""
    if float(value).is_integer():
        return str(int(value))
    return f"{value:.3f}".rstrip("0").rstrip(".")


# --------------------------------------------------------------------------
# Settings and PID definition files
# --------------------------------------------------------------------------

def load_settings(path: str) -> dict:
    settings = dict(DEFAULT_SETTINGS)
    if os.path.exists(path):
        try:
            with open(path, encoding="utf-8") as f:
                settings.update(json.load(f))
        except (OSError, ValueError):
            pass
    return settings


def save_settings(path: str, settings: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(settings, f, indent=2)


def _opt_float(value):
    if value is None or str(value).strip() == "":
        return None
    return float(value)


def signed(value: int, bits: int) -> int:
    value &= (1 << bits) - 1
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


FORMULA_FUNCS = {
    "abs": abs, "min": min, "max": max, "round": round,
    "int": int, "float": float, "math": math, "signed": signed,
}


class PidDef:
    """One channel: which PID to read and how to turn its bytes into a number.

    Formula variables:
      A B C D ...  data bytes after the PID echo (A = first byte)
      raw          all data bytes as one big-endian unsigned number
      sraw         same, signed
      n            number of data bytes
      d            list of the data bytes
    Functions: abs min max round int float signed(value, bits) math.*
    """

    def __init__(self, name, pid, formula="raw", units="raw", enabled=True,
                 every=1, verified=False, notes="", gauge_min=None, gauge_max=None):
        self.name = str(name).strip() or f"PID 0x{parse_pid(pid):02X}"
        self.pid = parse_pid(pid)
        self.formula = str(formula).strip() or "raw"
        self.units = str(units)
        self.enabled = bool(enabled)
        self.every = max(1, int(every))
        self.verified = bool(verified)
        self.notes = str(notes)
        self.gauge_min = _opt_float(gauge_min)     # None = scale the gauge bar to values seen
        self.gauge_max = _opt_float(gauge_max)
        if self.gauge_min is not None and self.gauge_max is not None and self.gauge_max <= self.gauge_min:
            raise ValueError("Gauge high must be above gauge low")
        self._code = compile(self.formula, f"<formula for {self.name}>", "eval")

    @classmethod
    def from_dict(cls, d: dict) -> "PidDef":
        return cls(d.get("name", ""), d["pid"], d.get("formula", "raw"), d.get("units", "raw"),
                   d.get("enabled", True), d.get("every", 1), d.get("verified", False),
                   d.get("notes", ""), d.get("gauge_min"), d.get("gauge_max"))

    def to_dict(self) -> dict:
        return {"name": self.name, "pid": f"0x{self.pid:02X}", "formula": self.formula,
                "units": self.units, "enabled": self.enabled, "every": self.every,
                "verified": self.verified, "notes": self.notes,
                "gauge_min": self.gauge_min, "gauge_max": self.gauge_max}

    def copy(self) -> "PidDef":
        return PidDef.from_dict(self.to_dict())

    @property
    def is_temp(self) -> bool:
        """Channels with units of degrees C (formula gives C) can be shown in C or F."""
        return self.units.strip().lower() == "\u00b0c"

    def units_for(self, unit: str) -> str:
        return "\u00b0F" if self.is_temp and unit == "F" else self.units

    def convert(self, value, unit: str):
        """Value in the channel's own units -> value in the chosen display unit (F or C)."""
        if value is None:
            return None
        return value * 9 / 5 + 32 if self.is_temp and unit == "F" else value

    def decode(self, data: bytes) -> float:
        env = dict(FORMULA_FUNCS)
        for i, letter in enumerate("ABCDEFGH"):
            env[letter] = data[i] if i < len(data) else 0
        raw = int.from_bytes(data, "big") if data else 0
        env.update(raw=raw, n=len(data), d=list(data),
                   sraw=signed(raw, 8 * len(data)) if data else 0)
        return float(eval(self._code, {"__builtins__": {}}, env))  # local tool, user-written formulas


ULX_NOTE = "Seen on a 2007 Ultra LX with key on and engine off. Check on your own ski."

DEFAULT_PIDS = [
    PidDef("Engine RPM", 0x09, "raw", "rpm", True, 1, False,
           "Assumes the 16-bit value is rpm. Not checked yet: it reads 00 00 with the engine off. At idle, "
           "compare with the ski's tach and note the raw hex.", 0, 8000),
    PidDef("Throttle sensor", 0x04, "max(0, min(100, (raw - 246) * 100 / (912 - 246)))", "%", True, 1, False,
           "Ultra LX: closed = 246 and wide open = 912 counts (about 1.2 V and 4.5 V if 10-bit at 5 V). "
           "Use Capture 0 % / 100 % in Edit to set your own ski's values.", 0, 100),
    PidDef("Intake pressure", 0x08, "raw * 0.124", "kPa", True, 1, False,
           "The PID returns counts, not kPa: 817 counts at key-on is about 101 kPa (3.99 V if 10-bit at 5 V). "
           "0.124 kPa per count makes key-on read ambient. Check idle vacuum (about 35-45 kPa). The ECU's own "
           "sensor curve would give the exact slope.", 0, 110),
    PidDef("Engine temp", 0x06, "raw - 40", "°C", True, 10, False,
           "Reads degrees C plus 40 (62 = 22 C = 72 F in a 70 F garage). One point only: check against an "
           "infrared thermometer once it's warm. Units of C make it switchable between C and F. "
           + ULX_NOTE, 0, 105),
    PidDef("Intake air temp", 0x07, "raw - 40", "°C", True, 10, False,
           "Same encoding as engine temp. " + ULX_NOTE, 0, 70),
    PidDef("Battery", 0x0A, "raw / 12.75", "V", False, 10, False,
           "Switched off: PID 0x0A returned 00 00 on an Ultra LX with key on, so it isn't battery voltage there. "
           "Use the PID scanner to find the right one (look for a value that jumps up when the engine runs).",
           0, 20),
]

# Raise DEFAULTS_REVISION whenever the built-in channels change in a way users should pick up. The app
# compares it with the number saved in the user's pids.json and offers to reset once if theirs is older.
DEFAULTS_REVISION = 1
DEFAULTS_NOTE = ("corrected readings for the Ultra LX, temperatures that follow the \u00b0F / \u00b0C switch, "
                 "and the throttle shown as 0 to 100 %")

PIDS_README = [
    "Channel definitions for kawasaki_logger.py. You can edit these in the app's Channels tab.",
    "pid: hex local identifier sent as '21 <pid>'. every: read on every Nth update (1 = every time).",
    "formula variables: A B C D... (data bytes), raw, sraw, n, d. Functions: abs min max round signed(v,bits) math.*",
    "verified: set true once a channel's scaling has been checked against a known-good reading.",
    "gauge_min / gauge_max: range of the tile's gauge bar. null = scale to the values seen this session.",
]


def save_pids(path: str, pids, revision=None) -> None:
    """revision=None stamps the file with the current defaults revision (use for fresh default lists)."""
    rev = DEFAULTS_REVISION if revision is None else int(revision)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"_readme": PIDS_README, "defaults_revision": rev, "pids": [p.to_dict() for p in pids]},
                  f, indent=2, ensure_ascii=False)


def pids_revision(path: str) -> int:
    """Defaults revision stamped in a channel file: 0 for older files that have no stamp.
    An unreadable file counts as current, so it never triggers the reset offer."""
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
    except (OSError, ValueError):
        return DEFAULTS_REVISION
    if not isinstance(doc, dict):
        return 0
    try:
        return int(doc.get("defaults_revision", 0))
    except (TypeError, ValueError):
        return 0


def load_pids(path: str):
    """-> (list of PidDef, list of problem strings). Creates the file with defaults if missing."""
    if not os.path.exists(path):
        save_pids(path, DEFAULT_PIDS)
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    items = doc.get("pids", []) if isinstance(doc, dict) else doc
    pids, problems = [], []
    for item in items:
        try:
            pids.append(PidDef.from_dict(item))
        except Exception as e:  # bad formula, bad pid, missing key
            problems.append(f"{item.get('name', '?')}: {e}")
    return pids, problems


# --------------------------------------------------------------------------
# K-line transport
# --------------------------------------------------------------------------

class KLine:
    def __init__(self, settings: dict, traffic=None):
        self.s = dict(settings)
        addr = str(self.s.get("ecu_addr", "auto")).strip().lower()
        self.ecu_candidates = list(ECU_AUTO_ADDRESSES) if addr in ("", "auto") else [parse_pid(addr)]
        self.ecu = self.ecu_candidates[0]                 # addresses are always hex
        self.tester = parse_pid(self.s["tester_addr"])
        self.baud = to_int(self.s["baud"])
        self.gap = to_int(self.s["request_gap_ms"]) / 1000.0
        self.timeout = to_int(self.s["response_timeout_ms"]) / 1000.0
        self.traffic = traffic or (lambda direction, data: None)
        self.ser = None
        self.echo = None            # None = unknown, True/False once detected
        self.key_bytes = b""
        self.last_activity = 0.0
        self._keepalive_payload = bytes([SID_TESTER_PRESENT, 0x01])

    # --- port -------------------------------------------------------------
    def open(self) -> None:
        port = self.s["port"]
        if port == DEMO_PORT:
            self.ser = FakeECUSerial(ecu_addr=self.ecu)
            return
        try:
            import serial
        except ImportError:
            raise KLineError("pyserial is not installed. Open a terminal and run: pip install pyserial")
        try:
            self.ser = serial.Serial(port=port, baudrate=self.baud, bytesize=8, parity="N",
                                     stopbits=1, timeout=0.005, write_timeout=1)
        except Exception as e:
            raise KLineError(f"Could not open {port}: {e}")
        try:
            self.ser.dtr = bool(self.s.get("dtr", True))
            self.ser.rts = bool(self.s.get("rts", False))
        except Exception:
            pass

    def close(self) -> None:
        if self.ser is not None:
            try:
                self.ser.close()
            except Exception:
                pass
        self.ser = None

    # --- session ----------------------------------------------------------
    def wake(self) -> None:
        """ISO 14230 fast init: bus idle >= 300 ms, 25 ms low, 25 ms high."""
        ser = self.ser
        ser.baudrate = self.baud
        ser.break_condition = False
        precise_sleep(0.35)
        ser.reset_input_buffer()
        if self.s.get("init_method") == "360baud":
            # A 0x00 byte at 360 baud = start bit + 8 zero bits = 25 ms low, then the stop bit goes high.
            ser.baudrate = 360
            t0 = time.perf_counter()
            ser.write(b"\x00")
            ser.flush()
            precise_sleep(0.035 - (time.perf_counter() - t0))
            ser.baudrate = self.baud
            precise_sleep(0.052 - (time.perf_counter() - t0))
        else:
            ser.break_condition = True
            precise_sleep(0.025)
            ser.break_condition = False
            precise_sleep(0.025)
        ser.reset_input_buffer()

    def start_session(self, attempts: int = 3) -> dict:
        err, found = None, False
        for _ in range(attempts):
            for addr in self.ecu_candidates:
                self.ecu = addr
                try:
                    self.wake()
                    self.echo = None
                    frame = build_frame(self.ecu, self.tester, [SID_START_COMM], length_in_fmt=True)
                    resp = self._transact(frame, SID_START_COMM)
                    self.key_bytes = bytes(resp[1:3])
                    found = True
                    break
                except KLineError as e:
                    err = e
            if found:
                break
        if not found:
            tried = ", ".join(f"0x{a:02X}" for a in self.ecu_candidates)
            raise NoResponse(f"ECU did not answer the wake-up handshake at {tried} ({err})")

        info = {"ecu": f"0x{self.ecu:02X}", "key_bytes": hexs(self.key_bytes), "echo": self.echo,
                "diag": "skipped", "baud": self.baud}
        diag = str(self.s.get("diag_session", "")).strip()
        if diag:
            try:
                self.request([SID_START_DIAG, parse_pid(diag)])
                info["diag"] = "accepted"
            except NegativeResponse as e:
                info["diag"] = f"refused ({nrc_name(e.code)})"
            except KLineError:
                info["diag"] = "no answer"

        switch = str(self.s.get("fast_switch_cmd", "")).strip()
        if switch:
            self.request(parse_hex(switch))
            self.ser.baudrate = to_int(self.s["fast_baud"])
            precise_sleep(0.05)
            self.ser.reset_input_buffer()
            info["baud"] = self.ser.baudrate
        return info

    def stop(self) -> None:
        try:
            self.request([SID_STOP_COMM], timeout=0.2)
        except KLineError:
            pass

    def alive(self) -> bool:
        """Any answer (even a refusal) proves the ECU is still listening."""
        try:
            self.request(self._keepalive_payload)
            return True
        except NegativeResponse:
            return True
        except KLineError:
            return False

    # --- requests ---------------------------------------------------------
    def request(self, payload, timeout: float | None = None) -> bytes:
        payload = bytes(payload)
        frame = build_frame(self.ecu, self.tester, payload)
        return self._transact(frame, payload[0], timeout)

    def read_local_id(self, pid: int) -> bytes:
        resp = self.request([SID_READ_LOCAL_ID, pid])
        if len(resp) < 2 or resp[1] != pid:
            raise KLineError(f"Answer was for a different PID: {hexs(resp)}")
        return bytes(resp[2:])

    def _read_until(self, buf: bytearray, need: int, deadline: float) -> bytearray:
        ser = self.ser
        while len(buf) < need and time.perf_counter() < deadline:
            waiting = ser.in_waiting
            chunk = ser.read(min(waiting, need - len(buf)) if waiting else 1)
            if chunk:
                buf += chunk
        return buf

    def _transact(self, frame: bytes, sid: int, timeout: float | None = None) -> bytes:
        ser = self.ser
        timeout = timeout or self.timeout
        wait = self.last_activity + self.gap - time.perf_counter()
        if wait > 0:
            precise_sleep(wait)
        try:
            ser.reset_input_buffer()
            ser.write(frame)
            self.traffic("TX", frame)
            buf = bytearray()

            # 1. Echo. Most USB K-line cables hear their own transmission on the shared wire.
            if self.echo is not False:
                byte_time = 10.0 / ser.baudrate
                deadline = time.perf_counter() + len(frame) * byte_time + 0.1
                while True:
                    while buf and buf[0] in (0x00, 0xFF):   # junk some adapters report after the wake pulse
                        del buf[0]
                    buf = self._read_until(buf, len(frame), deadline)
                    if not (buf and buf[0] in (0x00, 0xFF)) or time.perf_counter() >= deadline:
                        break
                if bytes(buf[:len(frame)]) == frame:
                    del buf[:len(frame)]
                    self.echo = True
                elif not buf:
                    if self.echo:
                        raise NoResponse("Cable did not echo the request (adapter unpowered or K-line not connected?)")
                    raise NoResponse(f"No reply to {hexs(frame)}")
                elif self.echo is None:
                    self.echo = False              # bytes came back but they are the ECU, not an echo
                else:
                    raise KLineError(f"Echo did not match what was sent (bus collision or noise): {hexs(buf)}")

            # 2. ECU response. Kawasaki uses 0x80-format headers, so leading 0x00/0xFF is line noise.
            deadline = time.perf_counter() + timeout
            while True:
                while True:
                    while buf and buf[0] in (0x00, 0xFF):
                        del buf[0]
                    if buf or time.perf_counter() >= deadline:
                        break
                    buf = self._read_until(buf, 1, deadline)
                if not buf:
                    raise NoResponse(f"No reply to {hexs(frame)}")
                total = frame_length(buf)
                while total is None and time.perf_counter() < deadline:
                    buf = self._read_until(buf, len(buf) + 1, deadline)
                    total = frame_length(buf)
                if total is None:
                    raise NoResponse(f"Reply cut off: {hexs(buf)}")
                buf = self._read_until(buf, total, deadline + 0.05)
                if len(buf) < total:
                    raise NoResponse(f"Reply cut off: {hexs(buf)}")
                rframe = bytes(buf[:total])
                del buf[:total]
                self.traffic("RX", rframe)
                if checksum(rframe[:-1]) != rframe[-1]:
                    raise BadChecksum(f"Bad checksum in {hexs(rframe)}")
                tgt, _src, payload = split_frame(rframe)
                if (tgt is not None and tgt != self.tester) or not payload:
                    continue
                if payload[0] == NEGATIVE_RESPONSE and len(payload) >= 3:
                    if payload[2] == 0x78:                       # response pending: ECU needs more time
                        deadline = time.perf_counter() + 5.0
                        continue
                    raise NegativeResponse(payload[1], payload[2])
                if payload[0] == (sid | 0x40):
                    return payload
                # A positive answer to some other service: ignore it and keep listening.
        finally:
            self.last_activity = time.perf_counter()


# --------------------------------------------------------------------------
# CSV log
# --------------------------------------------------------------------------

class CsvLog:
    def __init__(self, path: str, pids, include_raw: bool = True, temp_unit: str = "F"):
        self.path = path
        self.pids = pids
        self.unit = temp_unit
        self.cols = [i for i, d in enumerate(pids) if d.enabled]
        self.include_raw = include_raw
        folder = os.path.dirname(path)
        if folder:
            os.makedirs(folder, exist_ok=True)
        self.f = open(path, "w", newline="", encoding="utf-8")
        self.w = csv.writer(self.f)
        head = ["time", "elapsed_s", "marker"]
        head += [f"{pids[i].name} [{pids[i].units_for(temp_unit)}]" for i in self.cols]
        if include_raw:
            head += [f"{pids[i].name} raw (PID 0x{pids[i].pid:02X})" for i in self.cols]
        self.w.writerow(head)
        self.f.flush()
        self.t0 = time.time()
        self.rows = 0

    def row(self, values: dict, marker: str = "") -> None:
        now = time.time()
        r = [datetime.fromtimestamp(now).strftime("%H:%M:%S.%f")[:-3], f"{now - self.t0:.3f}", marker]
        r += [fmt_num(self.pids[i].convert(values.get(i, (None, "", None))[0], self.unit)) for i in self.cols]
        if self.include_raw:
            r += [values.get(i, (None, "", None))[1] for i in self.cols]
        self.w.writerow(r)
        self.f.flush()   # a ski can lose power at any moment; keep every row on disk
        self.rows += 1

    def close(self) -> None:
        try:
            self.f.close()
        except OSError:
            pass


# --------------------------------------------------------------------------
# Worker thread: owns the serial port so the GUI never blocks
# --------------------------------------------------------------------------

class Engine(threading.Thread):
    RETRY_SECONDS = 2.0
    MAX_FAILS = 3

    def __init__(self, events: queue.Queue):
        super().__init__(daemon=True, name="kline-engine")
        self.events = events
        self.cmds: queue.Queue = queue.Queue()
        self.running = True
        self.link: KLine | None = None
        self.session_ok = False
        self.next_retry = 0.0
        self.mode = "poll"                 # poll | scan | watch
        self.pids: list[PidDef] = []
        self.log: CsvLog | None = None
        self.marker = ""
        self.traffic_fp = None
        self.scan_list: list[int] = []
        self.watch_list: list[int] = []
        self.watch_pos = 0
        self.fail = 0
        self.settings: dict = {}
        self.reopen = False                # True while waiting for a lost USB cable to come back
        self.next_reopen = 0.0
        self._err_last = ("", 0.0)         # for rate-limiting repeated internal errors
        self._reset_poll()

    # --- plumbing ---------------------------------------------------------
    def send(self, *cmd) -> None:
        self.cmds.put(cmd)

    def emit(self, *event) -> None:
        self.events.put(event)

    def run(self) -> None:
        while self.running:
            try:
                self._commands()
                if not self.running:
                    break
                if self.link is None:
                    if self.reopen:
                        self._try_reopen()
                    else:
                        time.sleep(0.05)
                elif not self.session_ok:
                    self._try_session()
                elif self.mode == "scan":
                    self._scan_step()
                elif self.mode == "watch":
                    self._watch_step()
                else:
                    self._poll_step()
            except LinkLost as e:
                self._lost(str(e))
            except PortError as e:              # the USB serial port failed (unplugged, reset, lost power)
                self._cable_lost(e)
            except Exception as e:  # keep the worker alive no matter what
                self._internal_error(e)
        self._shutdown()

    def _internal_error(self, e: Exception) -> None:
        """Report an unexpected error, but never more than once per 5 s for the same one."""
        text, last = self._err_last
        now = time.perf_counter()
        if repr(e) != text or now - last > 5.0:
            self.emit("status", f"Internal error: {e!r}", "bad")
            self.emit("console", traceback.format_exc())
            self._err_last = (repr(e), now)
        time.sleep(0.2)

    def _commands(self) -> None:
        while True:
            try:
                cmd = self.cmds.get_nowait()
            except queue.Empty:
                return
            getattr(self, "_cmd_" + cmd[0])(*cmd[1:])

    def _traffic(self, direction: str, data: bytes) -> None:
        now = time.time()
        if self.traffic_fp:
            stamp = datetime.fromtimestamp(now).strftime("%H:%M:%S.%f")[:-3]
            self.traffic_fp.write(f"{stamp} {direction} {hexs(data)}\n")
        self.emit("traffic", now, direction, hexs(data))

    # --- commands from the GUI -------------------------------------------
    def _cmd_connect(self, settings: dict, pids) -> None:
        self._close_link()
        self.settings = dict(settings)
        self.pids = pids
        self._reset_poll()
        link = KLine(settings, traffic=self._traffic)
        try:
            link.open()
        except KLineError as e:
            self.emit("state", "disconnected", str(e))
            return
        self.link = link
        self.session_ok = False
        self.next_retry = 0.0
        self.mode = "poll"
        self.emit("state", "connecting", f"{settings['port']} open. Waking the ECU...")

    def _cmd_disconnect(self) -> None:
        self._cmd_stop_log()
        self._close_link()
        self.emit("state", "disconnected", "Disconnected.")

    def _cmd_set_pids(self, pids) -> None:
        self.pids = pids
        self._reset_poll()

    def _cmd_start_log(self, path: str, include_raw: bool, temp_unit: str = "F") -> None:
        self._cmd_stop_log()
        try:
            self.log = CsvLog(path, self.pids, include_raw, temp_unit)
        except OSError as e:
            self.log = None
            self.emit("status", f"Could not create the log file: {e}", "bad")
            self.emit("log", None, "", 0)
            return
        self.marker = ""
        self.emit("log", path, path, 0)

    def _cmd_stop_log(self) -> None:
        if self.log:
            self.log.close()
            self.emit("log", None, self.log.path, self.log.rows)
            self.log = None

    def _cmd_marker(self, text: str) -> None:
        self.marker = f"{self.marker} {text}".strip()

    def _cmd_scan(self, lo: int, hi: int) -> None:
        self.scan_list = list(range(lo, hi + 1))
        self.mode = "scan"

    def _cmd_watch(self, pids) -> None:
        self.watch_list = list(pids)
        self.watch_pos = 0
        self.mode = "watch"

    def _cmd_stop_scan(self) -> None:
        self.scan_list, self.watch_list = [], []
        self.mode = "poll"
        self.emit("scan_done")

    def _cmd_raw(self, payload: bytes) -> None:
        if not (self.link and self.session_ok):
            self.emit("console", "Not connected. Connect to the ECU first.")
            return
        try:
            resp = self.link.request(payload)
            self.emit("console", f"Reply: {hexs(resp)}")
        except NegativeResponse as e:
            self.emit("console", f"Refused: {e}")
        except KLineError as e:
            self.emit("console", f"No valid reply: {e}")

    def _cmd_traffic_file(self, path) -> None:
        if self.traffic_fp:
            self.traffic_fp.close()
            self.traffic_fp = None
        if path:
            try:
                folder = os.path.dirname(path)
                if folder:
                    os.makedirs(folder, exist_ok=True)
                self.traffic_fp = open(path, "a", encoding="utf-8", buffering=1)
                self.emit("status", f"Saving bus traffic to {path}", "info")
            except OSError as e:
                self.emit("status", f"Could not open traffic file: {e}", "bad")

    def _cmd_quit(self) -> None:
        self.running = False

    # --- link state -------------------------------------------------------
    def _close_link(self) -> None:
        self.reopen = False
        if self.link:
            if self.session_ok:
                try:
                    self.link.stop()
                except Exception:               # a dead port can't say goodbye
                    pass
            self.link.close()
        self.link = None
        self.session_ok = False
        self.scan_list, self.watch_list = [], []
        self.mode = "poll"

    def _cable_lost(self, err: Exception) -> None:
        port = self.settings.get("port", "the cable")
        if self.link:
            self.link.close()
        self.link = None
        self.session_ok = False
        self.fail = 0
        self.plan = []
        self.scan_list, self.watch_list = [], []
        self.mode = "poll"
        self.reopen = True
        self.next_reopen = time.perf_counter() + 1.0
        if self.log:
            self._cmd_marker("cable lost")
        self.emit("console", f"Port error on {port}: {err}")
        self.emit("state", "waiting",
                  f"Lost the USB cable on {port}. Plug it back in and the logger reconnects by itself. "
                  "If Windows gave it a new COM number, press Disconnect and pick it again.")

    def _try_reopen(self) -> None:
        if time.perf_counter() < self.next_reopen:
            time.sleep(0.05)
            return
        link = KLine(self.settings, traffic=self._traffic)
        try:
            link.open()
        except (KLineError, PortError, OSError):
            self.next_reopen = time.perf_counter() + 1.0      # still gone; keep trying quietly
            return
        self.link = link
        self.reopen = False
        self.session_ok = False
        self.next_retry = 0.0
        self.emit("state", "connecting", "Cable is back. Waking the ECU...")

    def _try_session(self) -> None:
        if time.perf_counter() < self.next_retry:
            time.sleep(0.05)
            return
        try:
            info = self.link.start_session(attempts=1)
        except KLineError as e:
            self.next_retry = time.perf_counter() + self.RETRY_SECONDS
            self.emit("state", "waiting",
                      "No answer from the ECU yet, still trying. If the ski shut itself off, take the key out "
                      f"and put it back in. ({e})")
            return
        self.session_ok = True
        self.fail = 0
        self.plan = []
        echo = {True: "yes", False: "no", None: "unknown"}[info["echo"]]
        self.emit("state", "connected",
                  f"Connected to ECU {info['ecu']}. Key bytes {info['key_bytes']}, diagnostic session {info['diag']}, "
                  f"cable echo {echo}, {info['baud']} baud.")
        if self.log:
            self._cmd_marker("reconnected")

    def _lost(self, reason: str) -> None:
        self.session_ok = False
        self.fail = 0
        self.plan = []
        self.next_retry = time.perf_counter() + 0.5
        self.emit("state", "waiting", f"Lost contact with the ECU ({reason}). Reconnecting... "
                                      "If the ski shut itself off, take the key out and put it back in.")

    def _problem(self, err: Exception) -> None:
        self.fail += 1
        if self.fail >= self.MAX_FAILS:
            raise LinkLost(str(err))

    # --- live polling -----------------------------------------------------
    def _reset_poll(self) -> None:
        self.plan: list[int] = []
        self.sweep_no = 0
        self.raw: dict = {}
        self.last_sweep_end = None
        self.rate = 0.0

    def _poll_step(self) -> None:
        if not self.plan:
            self.sweep_no += 1
            for d in self.pids:
                if d.enabled and (self.sweep_no - 1) % d.every == 0 and d.pid not in self.plan:
                    self.plan.append(d.pid)
            if not self.plan:
                self._idle()
                return
        pid = self.plan.pop(0)
        prev = self.raw.get(pid, (None, None, None))
        try:
            data = self.link.read_local_id(pid)
            self.raw[pid] = (data, None, time.time())
            self.fail = 0
        except NegativeResponse as e:
            self.raw[pid] = (None, f"ECU refused PID: {nrc_name(e.code)}", time.time())
            self.fail = 0
        except KLineError as e:
            self.raw[pid] = (prev[0], str(e), prev[2])
            self._problem(e)
        if not self.plan:
            self._finish_sweep()

    def _finish_sweep(self) -> None:
        now = time.perf_counter()
        if self.last_sweep_end is not None:
            dt = now - self.last_sweep_end
            inst = 1.0 / dt if dt > 0 else 0.0
            self.rate = inst if self.rate == 0 else 0.8 * self.rate + 0.2 * inst
        self.last_sweep_end = now
        values = {}
        for i, d in enumerate(self.pids):
            if not d.enabled:
                continue
            data, err, _t = self.raw.get(d.pid, (None, "not read yet", None))
            val = None
            if data is not None:
                try:
                    val = d.decode(data)
                except Exception as e:
                    err = f"formula error: {e}"
            values[i] = (val, hexs(data) if data is not None else "", err)
        self.emit("values", values, self.rate)
        if self.log:
            self.log.row(values, self.marker)
            self.marker = ""

    def _idle(self) -> None:
        if time.perf_counter() - self.link.last_activity > 1.5 and not self.link.alive():
            self._problem(NoResponse("keep-alive got no answer"))
        time.sleep(0.02)

    # --- PID discovery ----------------------------------------------------
    def _scan_step(self) -> None:
        if not self.scan_list:
            self.mode = "poll"
            self.emit("scan_done")
            return
        pid = self.scan_list[0]
        try:
            data = self.link.read_local_id(pid)
            self.emit("scan", pid, "answers", hexs(data), len(data))
            self.fail = 0
        except NegativeResponse as e:
            self.emit("scan", pid, f"refused ({nrc_name(e.code)})", "", 0)
            self.fail = 0
        except BadChecksum:
            self.emit("scan", pid, "bad checksum", "", 0)
        except KLineError:
            # Silence could mean "unsupported PID" or "ECU gone" - ask the ECU which.
            if not self.link.alive():
                raise LinkLost("ECU stopped answering during the scan")
            self.emit("scan", pid, "no answer", "", 0)
        self.scan_list.pop(0)

    def _watch_step(self) -> None:
        if not self.watch_list:
            self.mode = "poll"
            return
        pid = self.watch_list[self.watch_pos % len(self.watch_list)]
        self.watch_pos += 1
        try:
            data = self.link.read_local_id(pid)
            self.emit("watch", pid, hexs(data))
            self.fail = 0
        except NegativeResponse:
            pass
        except KLineError as e:
            self._problem(e)

    def _shutdown(self) -> None:
        self._cmd_stop_log()
        self._cmd_traffic_file(None)
        self._close_link()


# --------------------------------------------------------------------------
# Simulated ECU (DEMO port and tests)
# --------------------------------------------------------------------------

class EngineSim:
    """Synthetic engine: idle, a WOT pull to ~7600 rpm, hold, decel, repeat every 30 s."""

    def __init__(self):
        self.t0 = time.perf_counter()
        rnd = random.Random(1234)
        self.static = {pid: bytes(rnd.randrange(256) for _ in range(rnd.choice([1, 2, 2, 4])))
                       for pid in range(0x00, 0x63)}
        self.counter = 0

    def _state(self):
        el = time.perf_counter() - self.t0
        t = el % 30
        idle = 1250 + 25 * math.sin(el * 3)
        if t < 10:
            rpm, tps = idle, 0.0
        elif t < 14:
            f = (t - 10) / 4
            rpm, tps = 1250 + 6350 * f ** 0.8, 100.0
        elif t < 18:
            rpm, tps = 7600 + 40 * math.sin(el * 20), 100.0
        elif t < 22:
            rpm, tps = 7600 - 6350 * (t - 18) / 4, 0.0
        else:
            rpm, tps = idle, 0.0
        ect_c = min(55.0, 22 + el * 0.5)
        iat_c = 31 + 2 * math.sin(el / 20)
        return rpm, tps, ect_c, iat_c

    def read(self, pid: int):
        if pid > 0x62:
            return None
        rpm, tps, ect_c, iat_c = self._state()
        if pid == 0x09:
            return int(rpm).to_bytes(2, "big")
        if pid == 0x04:
            return int(246 + tps / 100 * 666).to_bytes(2, "big")
        if pid == 0x08:
            kpa = 40 + 57 * tps / 100 + 0.4 * math.sin(time.perf_counter() * 7)   # ~40 kPa idle, ~97 WOT
            return int(round(kpa / 0.124)).to_bytes(2, "big")
        if pid == 0x06:
            return int(round(ect_c + 40)).to_bytes(2, "big")
        if pid == 0x07:
            return int(round(iat_c + 40)).to_bytes(2, "big")
        if pid == 0x05:
            return int(round(101.3 / 0.124)).to_bytes(2, "big")
        if pid == 0x0A:
            volts = 14.1 if rpm > 2000 else 13.2                       # charging above idle
            return bytes([int(round(volts * 12.75))])
        if pid == 0x1F:
            self.counter = (self.counter + 1) & 0xFF
            return bytes([self.counter])
        return self.static[pid]


class FakeECUSerial:
    """Byte-level stand-in for a USB K-line cable plus a Kawasaki ECU."""

    def __init__(self, ecu_addr: int = 0x11, echo: bool = True, reply_delay: float = 0.03,
                 min_gap: float = 0.045):
        self.baudrate = 10400
        self.timeout = 0.005
        self.dtr, self.rts = True, False
        self.is_open = True
        self._addr = ecu_addr
        self._echo = echo
        self._delay = reply_delay
        self._min_gap = min_gap
        self._rx = bytearray()
        self._pending: list = []
        self._in = bytearray()
        self._brk = False
        self._brk_t = 0.0
        self._woken_at = None
        self._awake = False
        self._session = False
        self._last_reply = 0.0
        self.sim = EngineSim()

    @property
    def break_condition(self) -> bool:
        return self._brk

    @break_condition.setter
    def break_condition(self, value: bool) -> None:
        now = time.perf_counter()
        if value and not self._brk:
            self._brk_t = now
        elif not value and self._brk and 0.015 <= now - self._brk_t <= 0.04:
            self._woken_at = now
            self._in.clear()
        self._brk = bool(value)

    def write(self, data) -> int:
        data = bytes(data)
        if self.baudrate == 360:
            if data == b"\x00":
                self._woken_at = time.perf_counter() + 0.028
                self._in.clear()
            return len(data)
        if self._echo:
            self._rx += data
        self._in += data
        while True:
            n = frame_length(self._in)
            if n is None or len(self._in) < n:
                break
            frame = bytes(self._in[:n])
            del self._in[:n]
            if checksum(frame[:-1]) != frame[-1]:
                continue
            tgt, src, payload = split_frame(frame)
            if tgt != self._addr or not payload:
                continue
            if self._last_reply and time.perf_counter() - self._last_reply < self._min_gap:
                continue                    # real ECUs ignore requests that come too fast
            reply = self._respond(payload)
            if reply is not None:
                ready = time.perf_counter() + self._delay
                self._pending.append((ready, build_frame(src, self._addr, reply)))
                self._last_reply = ready
        return len(data)

    def _respond(self, p: bytes):
        sid = p[0]
        if sid == SID_START_COMM:
            if self._woken_at is None or time.perf_counter() - self._woken_at > 0.3:
                return None
            self._awake = True
            return [0xC1, 0xEA, 0x8F]
        if not self._awake:
            return None
        if sid == SID_START_DIAG:
            self._session = True
            return [0x50, p[1] if len(p) > 1 else 0x80]
        if sid == SID_TESTER_PRESENT:
            return [0x7E]
        if sid == SID_STOP_COMM:
            self._awake = self._session = False
            return [0xC2]
        if sid == SID_READ_LOCAL_ID:
            if len(p) < 2:
                return [0x7F, sid, 0x12]
            if not self._session:
                return [0x7F, sid, 0x22]
            data = self.sim.read(p[1])
            if data is None:
                return [0x7F, sid, 0x12]
            return [0x61, p[1]] + list(data)
        return [0x7F, sid, 0x11]

    def _move_ready(self) -> None:
        now = time.perf_counter()
        keep = []
        for ready, frame in self._pending:
            if ready <= now:
                self._rx += frame
            else:
                keep.append((ready, frame))
        self._pending = keep

    @property
    def in_waiting(self) -> int:
        self._move_ready()
        return len(self._rx)

    def read(self, n: int = 1) -> bytes:
        deadline = time.perf_counter() + (self.timeout or 0)
        while True:
            self._move_ready()
            if self._rx or time.perf_counter() >= deadline:
                break
            time.sleep(0.001)
        out = bytes(self._rx[:n])
        del self._rx[:n]
        return out

    def reset_input_buffer(self) -> None:
        self._move_ready()
        self._rx.clear()

    def flush(self) -> None:
        pass

    def close(self) -> None:
        self.is_open = False
