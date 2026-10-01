#!/usr/bin/env python3
"""Checks the protocol code against the simulated ECU. Run: python selftest.py  (no ski or cable needed)"""
import time

import kline as kl


def main():
    # Frames must match what Kawasaki ECUs expect byte for byte.
    assert kl.hexs(kl.build_frame(0x11, 0xF1, [0x81], length_in_fmt=True)) == "81 11 F1 81 04"
    assert kl.hexs(kl.build_frame(0x11, 0xF1, [0x10, 0x80])) == "80 11 F1 02 10 80 14"
    assert kl.hexs(kl.build_frame(0x11, 0xF1, [0x21, 0x09])) == "80 11 F1 02 21 09 AE"
    print("Frame building ........ ok")

    for method in ("break", "360baud"):
        link = kl.KLine(dict(kl.DEFAULT_SETTINGS, port=kl.DEMO_PORT, init_method=method))
        link.open()
        info = link.start_session()
        assert info["key_bytes"] == "EA 8F" and info["diag"] == "accepted", info
        rpm = kl.DEFAULT_PIDS[0].decode(link.read_local_id(0x09))
        assert 0 < rpm < 9000
        link.stop()
        link.close()
        print(f"Handshake ({method:7s}) ... ok, demo RPM {rpm:.0f}")

    link = kl.KLine(dict(kl.DEFAULT_SETTINGS, port=kl.DEMO_PORT))
    link.open()
    link.start_session()
    t0, n = time.perf_counter(), 0
    while time.perf_counter() - t0 < 1.5:
        link.read_local_id(0x09)
        n += 1
    link.close()
    print(f"Request rate .......... {n / 1.5:.1f} per second (demo ECU)")
    print("\nAll good. Start the app with: python kawasaki_logger.py")


if __name__ == "__main__":
    main()
