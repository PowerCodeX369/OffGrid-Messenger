# OffGrid Android Phase 2.3 — Binary Media Upload

Open this `android` directory in Android Studio, build and run as an **in-place upgrade**.
Keep your saved pairing key. **Firmware update on the phone-side ESP32 is required to activate binary upload.**
If that ESP32 still runs Phase 2.2/2.0.9 firmware, this Android version keeps using ASCII MSGHEX automatically; functionality remains compatible.
Look in Logcat `tag:OffGridBLE` for `Binary BLE capability confirmed; active=true` after connection.
Short text, delivery/read/played receipts, incoming photos/voice and Wi-Fi/ESP-NOW are preserved.
See `PHASE_2_3_BINARY_BLE.md` and the complete project's `README_PHASE_2_3.md`.
