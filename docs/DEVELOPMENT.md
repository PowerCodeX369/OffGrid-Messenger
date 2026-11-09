# Build and development guide

This guide separates **documented steps** from **verified builds**. The provided repository was inspected, but no Android SDK or ESP-IDF toolchain was available in the preparation environment, and no physical devices were connected.

## Prerequisites

- Two **ESP32-C3 Super Mini** devices and suitable USB data cables.
- ESP-IDF with ESP32-C3 support, including BLE/NimBLE (the firmware README specifies ESP-IDF 5.2+ and recommends a stable 5.x; match a supported environment before building).
- Android Studio, Android SDK 36, JDK 17 and a Gradle installation capable of the Android Gradle Plugin 8.13.2 / Kotlin 2.4.20 setup in `android/build.gradle.kts`.
- Chromium-family browser with Web Serial capability, for the optional browser client.

## Firmware (ESP32-C3)

```bash
cd firmware
idf.py set-target esp32c3
idf.py reconfigure
idf.py build
# Only when intentionally flashing a device, after backing up pairing state:
# idf.py -p /dev/ttyACM0 flash monitor
```

Before any flash, read [firmware/NIMBLE_BUILD_AND_FLASH.md](../firmware/NIMBLE_BUILD_AND_FLASH.md) and [firmware/README.md](../firmware/README.md); examine flash partitions and NVS implications. **Do not run flash, erase-flash or factory reset as part of ordinary repository validation.** Never assume two board ports are interchangeable.

## Android

1. Open **`android/`** in Android Studio, choose JDK 17, let the IDE resolve supported Gradle tooling and synchronize the project.
2. The snapshot **does not contain `gradlew` or Gradle wrapper metadata**; a clean command-line build is therefore not guaranteed out of the box. Add/pin a reviewed wrapper in a separate change after confirming the compatible Gradle version.
3. `android/app/build.gradle.kts` specifies `compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`, `versionCode = 256`, and version `2.5.6-link-background`.
4. Build an APK through Android Studio, **then** test on a compatible phone and firmware. Preserve Android app ID and signing cert; update over the installed version rather than uninstalling to preserve local encrypted data and keys where applicable.
5. See [android/README.md](../android/README.md) and version-specific Android documentation for device workflows. Do not assume a BLE connection until confirmed by diagnostics.

## Browser

Use the standalone files under `web/` in a Chromium browser supporting Web Serial. Web Serial generally requires a secure context such as `localhost` and explicit permission. For example, from the repository root:

```bash
python3 -m http.server 8000 --directory web
```

Open `http://localhost:8000`, select your serial device in the browser and follow the firmware's provisioning steps. Do not expose the local server to an untrusted network. `web/` is the sole maintained browser source. ESP-IDF firmware does not bundle this directory; browser access is through USB Web Serial.

## Test without hardware

Read [docs/TESTING.md](TESTING.md) for available contract tests, checks and exact limitations. Existing tests and successful syntax checks do **not** establish build, security or physical radio quality.

## Known documentation limitation

An older Android README referenced `../README_FIRST.md`, but that file is not in this repository. Use the guides listed here and release notes instead. Maintainers should verify any version-specific instructions against source code before making hard compatibility promises.
