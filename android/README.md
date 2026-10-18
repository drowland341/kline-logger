# K-line Logger (Android)

A Kotlin / Jetpack Compose app for live data and diagnostics from Kawasaki personal watercraft. It connects through a wireless **ELM327 adapter** (Bluetooth, Bluetooth LE or Wi-Fi), which is the way to use it on the water, or through a **USB K-line cable** for diagnosis on the trailer. Both speak the same Kawasaki KWP2000 dialect as the Windows app (`kline.py`, ported to Kotlin): `StartDiagnosticSession`, `ReadDataByLocalIdentifier`, `TesterPresent`.

Not affiliated with or endorsed by Kawasaki.

## Requirements

- Android 8.0 (API 26) or newer
- One of:
  - an **ELM327 adapter**: Bluetooth Classic (pair it in Android settings first), Bluetooth LE (Veepeak BLE, Vgate iCar Pro, OBDLink CX and similar), or Wi-Fi (join its network first; most use 192.168.0.10 port 35000);
  - a **USB K-line cable** that shows up as a serial port: FTDI (KKL 409.1), CH340/CH341, PL2303 or CP210x, plus a USB-C or micro-USB OTG adapter. Plugging one in offers to open the app.
- The Kawasaki 4-pin diagnostic adapter
- To build: Android Studio (recent stable), JDK 11+

## Features

- **Dashboard**: large gauges for the main channels, readable on a mounted phone. The screen stays on while connected.
- **Live Data**: a tile per channel with value, sparkline and raw bytes, plus GPS speed from the phone. Add, edit or delete channels in the app (formula, units, gauge range, read rate). Saved in the Windows app's `pids.json` format, so the file can be copied between the apps. Temperature, pressure and speed units can each be switched; logs keep °C, kPa and mph.
- **Recording**: CSV logs saved to **Documents/KlineLogger** (Android 10+, no storage permission needed), so they show up in the Files app and on a PC over USB. Older Android uses the app-private folder. The Connection tab lists every log with Share and Delete.
- **PID Scanner**: scans a range of local identifiers, then **Watch** re-reads the ones that answered and tracks low/high, swing and changes to help identify unknown sensors. Results export to CSV, and any row can be turned into a channel. After a scan, the **Research Log** records every channel, GPS speed and the raw bytes of every PID found, once a second, in the same layout as the iOS app.
- **Codes**: reads stored service codes (`18 00 FF 00`) and looks each one up in the Ultra LX service code table (meter characters, title, possible causes, fail-safe behaviour, ECU pins to check). **Clear Codes** (`14 FF 00`) asks for confirmation first. A protocol debug panel shows the raw request and response frames.
- **Connection**: pick the adapter, connect/disconnect, ECU and adapter details, the log list, and a raw TX/RX traffic log (every ELM327 command and reply, too).
- **Demo mode**: a simulated ECU behind the same byte-level pipeline (echo, framing, checksums, timing), so everything can be tried without a ski. It reports codes 14 and 72.

## Build and run

1. Open the `android/` folder (not the repo root) in Android Studio.
2. Let Gradle sync. Android Studio creates `local.properties` with your SDK path; it's git-ignored.
3. Run the `app` configuration on a phone or emulator. In the emulator, turn on **Demo mode** on the Connection tab.

From the command line: `./gradlew assembleDebug testDebugUnitTest` (`gradlew.bat` on Windows).

## Project structure

| Path | What it is |
|---|---|
| `app/src/main/java/.../kline/` | KWP2000 framing (`Kwp.kt`) and the K-line session/request logic (`KLine.kt`), ported from `kline.py` |
| `app/src/main/java/.../elm/` | ELM327 driver (`Elm327.kt`) and the Bluetooth, Bluetooth LE and Wi-Fi links |
| `app/src/main/java/.../usb/` | USB serial cable access (usb-serial-for-android) |
| `app/src/main/java/.../demo/` | Simulated ECU for Demo mode |
| `app/src/main/java/.../channels/` | Channel definitions, formula evaluator, saved channel list |
| `app/src/main/java/.../logging/` | CSV recorder, research log and `LogStore` (Documents/KlineLogger) |
| `app/src/main/java/.../settings/`, `location/` | Adapter and unit preferences; GPS speed |
| `app/src/main/java/.../codes/` | Service code table loader, DTC request bytes (`DtcProtocol`) and reply parser |
| `app/src/main/java/.../ui/` | Compose screens, components and theme |
| `app/src/main/assets/` | `ultra_lx_service_codes.json`. The built-in channels come from [`../shared/channels/`](../shared/channels/), added as an assets folder |
| `app/src/test/` | Unit tests (framing, formulas, demo ECU, DTC parsing, ELM327, shared channel files) |

## Known PIDs

The built-in channels live in [`shared/channels/kawasaki.json`](../shared/channels/kawasaki.json), the same file the Windows and iOS apps use, so a change there reaches all three. See [`shared/README.md`](../shared/README.md) for the format and for raising `defaults_revision` so existing users are offered new defaults.

## Protocol notes

See the root [README](../README.md#protocol-notes) for the frame format and timing. The fault-code request bytes haven't been confirmed on a ski yet. They're all in `DtcProtocol` (`codes/ServiceCodes.kt`), along with how code numbers are decoded, so they can be changed in one place once the debug panel shows what the ECU actually answers.
