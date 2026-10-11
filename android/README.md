# K-line Logger (Android)

A Kotlin / Jetpack Compose app for live data and diagnostics from Kawasaki personal watercraft. It drives the K-line directly through a USB K-line cable plugged into the phone, using the same protocol code as the Windows app (`kline.py`, ported to Kotlin): ISO 14230 fast init, `StartCommunication`, `StartDiagnosticSession`, `ReadDataByLocalIdentifier`.

Not affiliated with or endorsed by Kawasaki.

## Requirements

- Android 8.0 (API 26) or newer, with USB host (OTG) support
- A USB K-line cable that shows up as a serial port: FTDI (KKL 409.1), CH340/CH341, PL2303 or CP210x. Plugging one in offers to open the app.
- A USB-C or micro-USB OTG adapter, and the Kawasaki 4-pin diagnostic adapter
- To build: Android Studio (recent stable), JDK 11+

## Features

- **Dashboard**: large gauges for the main channels, readable on a mounted phone. The screen stays on while connected.
- **Live Data**: a tile per channel with value, sparkline and raw bytes. Add, edit or delete channels in the app (formula, units, gauge range, read rate). Saved in the Windows app's `pids.json` format, so the file can be copied between the two.
- **Recording**: CSV logs to `Android/data/com.rowlandrestorations.klinelogger/files/logs`.
- **PID Scanner**: scans a range of local identifiers, then **Watch** re-reads the ones that answered and tracks low/high, swing and changes to help identify unknown sensors. Results export to CSV, and any row can be turned into a channel.
- **Codes**: reads stored service codes (`18 00 FF 00`) and looks each one up in the Ultra LX service code table (meter characters, title, possible causes, fail-safe behaviour, ECU pins to check). **Clear Codes** (`14 FF 00`) asks for confirmation first. A protocol debug panel shows the raw request and response frames.
- **Connection**: connect/disconnect, ECU handshake details, and a raw TX/RX K-line log.
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
| `app/src/main/java/.../usb/` | USB serial cable access (usb-serial-for-android) |
| `app/src/main/java/.../demo/` | Simulated ECU for Demo mode |
| `app/src/main/java/.../channels/` | Channel definitions, formula evaluator, saved channel list |
| `app/src/main/java/.../codes/` | Service code table loader, DTC request bytes (`DtcProtocol`) and reply parser |
| `app/src/main/java/.../ui/` | Compose screens, components and theme |
| `app/src/main/assets/` | Bundled `pids.json` and `ultra_lx_service_codes.json` |
| `app/src/test/` | Unit tests (framing, formulas, demo ECU, DTC parsing) |

## Known PIDs

`app/src/main/assets/pids.json` matches the root-level `pids.json` used by the Windows app. If you add or confirm a channel on one platform, port the change to the others.

## Protocol notes

See the root [README](../README.md#protocol-notes) for the frame format and timing. The fault-code request bytes haven't been confirmed on a ski yet. They're all in `DtcProtocol` (`codes/ServiceCodes.kt`), along with how code numbers are decoded, so they can be changed in one place once the debug panel shows what the ECU actually answers.
