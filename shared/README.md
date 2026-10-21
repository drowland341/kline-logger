# Shared channel lists

The Windows, iOS and Android apps all read their built-in channels from this folder, so a PID or formula
fixed here is fixed everywhere. There is one file per manufacturer:

| File | Manufacturer | Protocol |
|---|---|---|
| `channels/kawasaki.json` | Kawasaki | KWP2000 over K-line (`21 <pid>` reads, ECU at 0x11 or 0x28) |
| `channels/kawasaki_sxr160.json` | Kawasaki SX-R 160 (2024) | Same protocol. SXR160 readings, notes and the codes seen on it, kept for reference; the apps still load `kawasaki.json` |

How each app picks the file up:

- **Windows** (`kline.py`) loads it at startup. `KlineLogger.spec` bundles the folder into the installer.
- **iOS**: the Xcode project adds `shared/channels` to the app target as a synchronized folder, so every
  JSON file here is copied into the app bundle.
- **Android**: `android/app/build.gradle.kts` adds `shared/channels` as an assets folder.

Each app keeps the user's own editable copy separately (`pids.json` in the app's data folder) and starts
it from this file. The user's copy is never written back here.

## File format

```json
{
  "_readme": ["free-text notes, ignored by the apps"],
  "manufacturer": "Kawasaki",
  "protocol": "kawasaki-kwp2000",
  "defaults_revision": 2,
  "defaults_note": "what changed in this revision, shown in the update prompt",
  "pids": [
    {
      "name": "Engine RPM",
      "pid": "0x09",
      "formula": "raw * 4",
      "units": "rpm",
      "enabled": true,
      "every": 1,
      "priority": true,
      "verified": false,
      "notes": "how the scaling was worked out",
      "gauge_min": 0,
      "gauge_max": 8000
    }
  ]
}
```

- **pid**: the hex local identifier the ECU is asked for.
- **formula**: turns the reply bytes into a value. Variables: `A B C D...` (data bytes), `raw` (all bytes as
  one big-endian number), `sraw` (signed), `n` (byte count), `d` (byte list). Functions: `abs min max round
  signed(v, bits) math.*`. All three apps evaluate the same Python-style subset, so keep to it.
- **units**: write temperatures in `°C`. Each app converts to °F for display, and logs stay in °C.
- **every**: read on every Nth update (1 = every time). **priority** (phone apps only) reads the
  channel on every update, ahead of the rest, for gauges that must track quickly, like RPM and throttle.
  On Android the other channels then take turns, one per update, as their `every` comes round.
- **verified**: set true once a scaling has been checked against a known-good reading.
- **gauge_min / gauge_max**: the gauge range. `null` scales the gauge to the values seen.

## Changing the defaults

Edit the file, then raise `defaults_revision` by one and say what changed in `defaults_note`. On their
next start, existing users of all three apps are asked once whether to switch to the new list. They can
keep their own if they've added channels or calibrated the throttle.

Before committing, run the checks. They load every file in this folder:

- `python selftest.py` (Windows app, from the repo root)
- `./gradlew testDebugUnitTest` (Android app, from `android/`)

## Adding a manufacturer

Add a new file, for example `channels/yamaha.json`, with its own `manufacturer`, `protocol` and
`defaults_revision`. The checks above pick it up automatically. Today the apps only load `kawasaki.json`
and only speak Kawasaki's KWP2000. A new manufacturer still needs work in each app:

- a protocol driver for that make's ECU (bus, addresses and request format);
- a way to choose the make, so the app loads that make's file.

The `protocol` field is there so each app can match a file to its driver.
