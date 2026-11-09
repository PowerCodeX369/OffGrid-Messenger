# OffGrid Messenger — Android v2.5.6

This is the current reconstructed Android client (Kotlin/Compose, BLE). For system setup and test boundaries, start with [the development guide](../docs/DEVELOPMENT.md), [test matrix](../docs/TESTING.md) and [architecture](../docs/ARCHITECTURE.md).

## Android Studio setup

1. Open **this `android/` folder** as the project in Android Studio; select JDK 17.
2. Configure Android SDK 36 and synchronize the project. Current source specifies Android Gradle Plugin 8.13.2 and Kotlin 2.4.20.
3. **No Gradle wrapper is included** in this historical snapshot. You may need a locally compatible Gradle setup; a reviewed wrapper should be added separately for reproducibility.
4. Build/install using Android Studio on a compatible physical phone. Keep the existing application ID (`com.offgrid.messenger`) and the **same signing certificate** if installing over an existing copy. Do not uninstall the app simply to troubleshoot: preserved local history and encryption key material may be lost.
5. Connect to the compatible OffGrid BLE firmware and confirm GATT service discovery and authenticated connection in diagnostics before testing media or receipts.

Do **not** reflash the ESP32s or erase NVS as part of an Android UI/connection change unless a specific firmware migration has been approved. Keep the shared key private.

## Reference notes

- [Phase 2.3 binary BLE](PHASE_2_3_BINARY_BLE.md)
- [Phase 2.4 interface](PHASE_2_4_PREMIUM.md)
- [Phase 2.5 camera and polish](PHASE_2_5_CAMERA_POLISH.md)
- [Phase 2.0.8 BLE diagnostics](ANDROID_BLE_FIX_208.md)

Existing host tests can check source contracts, but the complete Android build and phone/BLE operation must be independently verified. The older README reference to `../README_FIRST.md` was stale; that file is not present in this reconstructed repository.
