# OffGrid Messenger Android 2.5

Open this directory in Android Studio. See `PHASE_2_5_CAMERA_POLISH.md` and `../README_FIRST.md` for changes, dependencies and validation. Keep the same application ID and install as an update; do not uninstall or flash your ESP32s.

# OffGrid Messenger Android 2.4

Import this folder directly into Android Studio. Run over the existing Phase 2.3 app without
uninstalling it. Your saved 43-character pairing key stays in Android Keystore.

**Do not flash firmware.** Phase 2.3 binary BLE/ESP-NOW firmware works unchanged.

Features: premium Material 3-inspired responsive UI, glass/acrylic surfaces, native launcher icon,
correct navigation/keyboard insets, encrypted device history, system MessagingStyle notifications,
press-and-hold voice, photo preview, and opt-in connected-device foreground service.

Tests: `bash tests/run_phase24_tests.sh` (host tests); Android Studio build and device tests pending.
Full instructions and limitations in `PHASE_2_4_PREMIUM.md` and the complete-release README.
