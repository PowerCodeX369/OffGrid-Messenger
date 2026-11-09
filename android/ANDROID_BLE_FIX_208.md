# BLE selection issue fixed in Phase 2.0.8

The first version used `BluetoothDevice.EXTRA_DEVICE` for CompanionDeviceManager results. It is now corrected to `CompanionDeviceManager.EXTRA_DEVICE`, with `AssociationInfo` fallback and modern `onAssociationCreated` callback. The GATT client now emits diagnostic messages in the More menu and Logcat tag `OffGridBLE`.

To reset an incorrect saved BLE MAC without changing your AES key: open the More menu -> `Forget BLE device (keep key)` -> Pair again. This does not clear NVS or phone Keystore pairing secrets.

No ESP32 flash required. Full Android Gradle build is pending user device/SDK verification.
