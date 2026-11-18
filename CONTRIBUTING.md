# Contributing to Ruta

Thanks for helping. Bug reports, logs from other Vida models, protocol findings, fixes and
design polish are all welcome.

## Ground rules

- **No decompiled code.** Never paste, translate or paraphrase code from the official app or any
  other proprietary app. Protocol knowledge goes into [docs/PROTOCOL.md](docs/PROTOCOL.md) in your
  own words, based on what you observed on the Bluetooth link.
- **Plaintext channels only.** Ruta never uses the scooter's encrypted command service (service
  two, `0x0201`–`0x0203`) and never tries to bypass anything the scooter or its maker enforces.
  Pull requests that do will be closed.
- **No personal identifiers.** No real MAC addresses, BLE names, VINs, IMEIs, PINs, phone numbers
  or home/work locations in code, tests, docs, screenshots or logs. Use synthetic ones
  (`HMC_000000`, `00:11:22:33:44:55`, `MBLTEST…`) and fictional places.
- **Never commit keys.** No `*.jks` / `*.keystore`, passwords or `local.env`.
- By contributing you agree that your work is licensed under the [Apache License 2.0](LICENSE).

## Build

Requirements:

- JDK 17+ and Kotlin (`kotlinc`) on `PATH`. The Kotlin lib folder must contain `kotlin-stdlib.jar`
  and `kotlinx-coroutines-core-jvm.jar`; it is found next to `kotlinc`, or set `KOTLIN_LIB`.
- Android SDK with `build-tools;36.0.0` and `platforms;android-36`. Set `ANDROID_HOME` (defaults:
  `~/Library/Android/sdk`, `~/Android/Sdk`); `ANDROID_BUILD_TOOLS` / `ANDROID_PLATFORM` override
  the versions.
- Python 3 (APK checks).

```sh
bash test.sh                  # host tests (JVM)
bash build.sh --compile-only  # kotlinc only; no key needed (this is what CI runs)
bash build.sh --resources-only
bash build.sh                 # signed APK in dist/, verified by tests/check_apk.py
bash build.sh --unsigned      # same APK, unsigned (F-Droid); no key needed
adb install -r dist/ruta-*.apk
```

There is no Gradle. The pipeline is kotlinc › d8 › aapt2 › zipalign › apksigner, and the APK
contains only this project's Kotlin, kotlin-stdlib, kotlinx-coroutines-core and `res/`.

### Signing

`bash build.sh` stops with an error when no key is configured; it never falls back to a debug key.
It reads these from the environment, then from a git-ignored `local.env` next to `build.sh`
(environment wins):

| Variable | Meaning |
|---|---|
| `RUTA_KEYSTORE` | Path to the keystore |
| `RUTA_KEY_ALIAS` | Key alias |
| `RUTA_KEYSTORE_PASS` | Keystore password |
| `RUTA_KEY_PASS` | Key password (defaults to the keystore password) |
| `RUTA_SIGNER_SHA256` | Expected certificate SHA-256 (defaults to the official Ruta key) |

`tests/check_apk.py` fails the build if the APK is not signed by the expected certificate. For a
fork or a local build, make your own key, keep it outside the repository, and set
`RUTA_SIGNER_SHA256`:

```sh
keytool -genkeypair -keystore ~/ruta-dev.jks -alias ruta -keyalg RSA -keysize 4096 -validity 10000
keytool -list -v -keystore ~/ruta-dev.jks -alias ruta | grep SHA256   # -> RUTA_SIGNER_SHA256
```

A build signed with your key cannot update the Ruta release from GitHub (and the other way round): uninstall
first, which deletes that install's history.

## Test

- `bash test.sh` compiles the pure-Kotlin files (no `android.*` imports) with `tests/*Test.kt` and
  runs them on the JVM: protocol frames, navigation parsing, road names, range and range check,
  ride math, pairing, parking rules, demo data.
- `tests/check_apk.py` checks the built APK (identity, version, label, signing certificate,
  contents); `tests/check_icons.py` checks that every icon name used in code exists in
  `res/drawable`.
- New logic belongs in a pure file with a host test where possible.

## Demo mode

Demo mode simulates a scooter, a ride, navigation, charging, a call and music, without Bluetooth.
Use it for UI work and screenshots. Start it in the app (Welcome › Explore demo, or More ›
Diagnostics › Demo mode) or from a computer:

```sh
adb shell am start -n dev.codesurgen.ruta/.MainActivity --es demo riding
adb shell am start -n dev.codesurgen.ruta/.MainActivity --es demo navigating --ez demo_seed true   # fresh demo history
adb shell am start -n dev.codesurgen.ruta/.MainActivity --es demo off
```

`demo` takes `parked`, `riding`, `navigating`, `charging`, `call`, `music` or `off`. Demo history
lives in its own database, deleted when the demo ends. Screens read everything through `Host`, so
they need no demo branches; never start Bluetooth, the ride service or a notification without a
demo guard (see [docs/UIKIT.md](docs/UIKIT.md) §10).

## Logs

More › Diagnostics shows, copies and shares the log. Every line also goes to daily files kept for
7 days:

```sh
adb pull /sdcard/Android/data/dev.codesurgen.ruta/files/logs
adb logcat -s Ruta
```

Logs contain the scooter's Bluetooth name and address. Remove them (and any locations) before
attaching a log to an issue.

## Protocol work

[docs/PROTOCOL.md](docs/PROTOCOL.md) describes every characteristic Ruta uses. Diagnostics can
probe the stats channel and log raw frames; findings from other models or firmware versions are
very welcome. Describe what you observed (bytes, conditions, what the scooter display showed),
not where it came from in someone else's code.

## Code style

- Kotlin, framework views only: no AppCompat, androidx or Material libraries, no Jetpack Compose.
- Build UI with the in-app kit ([docs/UIKIT.md](docs/UIKIT.md)) and follow
  [docs/DESIGN.md](docs/DESIGN.md): numbers and icons, not sentences; Material 3 Expressive.
- No `R` class: resources resolve by name (`getIdentifier(name, "drawable", packageName)`).
- Never use `Dispatchers.Main`; use `Ui.scope()` / `Ui.dispatcher`.
- `onDraw` allocates nothing.
- Pure-logic files have no `android.*` imports, so they can be host-tested.
- Never log PINs, the IMEI, the VIN, coordinates or place names.
- Keep changes focused; match the surrounding style; run `bash test.sh` and
  `bash build.sh --compile-only` before opening a pull request.

## Source map

| File | Role |
|---|---|
| `MainActivity.kt`, `Host.kt` | Shell (tabs, navigation bar, snackbars, sheets, permissions, lifecycle) and the shell ↔ screen contract |
| `HomeScreen.kt`, `InstrumentCluster.kt` | Home and the cluster gauge |
| `NavigateScreen.kt`, `RidesScreen.kt`, `InsightsScreen.kt`, `MoreScreen.kt`, `Onboarding.kt` | The other tabs and the first-run / add-scooter flow |
| `Theme.kt`, `Icons.kt`, `Components.kt`, `Motion.kt`, `UiKit.kt`, `Charts.kt` | Material 3 Expressive design system on framework views |
| `ScooterConnection.kt`, `TcuProtocol.kt` | GATT client and frame encoders / parsers |
| `Pairing.kt`, `PairingLogic.kt`, `SecureStore.kt` | Scan, bond with the saved PIN, saved scooters (PIN encrypted with an Android Keystore key) |
| `MapsNavigation.kt`, `NavigationBridge.kt`, `NavCountdown.kt`, `RoadNames.kt` | Maps notification parsing, distance countdown, road names that fit the display |
| `PhoneBridge.kt` | Media sessions, volume, calls, missed-call and message counts, headset |
| `BatteryAnalytics.kt`, `Range.kt`, `RideMath.kt`, `RideTracker.kt`, `RideService.kt`, `TelemetryStore.kt` | BMS decoding, range model, charge ETA and alerts, ride recording (foreground service), SQLite history and CSV export |
| `RangeCheck.kt`, `Alerts.kt` | Range check against the Maps route; rider alerts |
| `Parking.kt`, `ParkingRules.kt` | The remembered parked spot and when to save it |
| `Demo.kt`, `DemoData.kt` | Demo scenarios and synthetic history |
| `DiagLog.kt`, `Platform.kt` | Diagnostics log; main-thread coroutine dispatcher |

## Reporting bugs

Include your scooter model, Android version and phone, the Ruta version (More › About), what you
expected and what happened, and a log with identifiers removed.
