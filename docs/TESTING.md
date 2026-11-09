# Validation and test matrix

## Safe preflight (no hardware writes)

```bash
python3 scripts/repository_check.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
```

These checks validate the repository structure, release references, and selected metadata, not firmware or app function.

## Existing test entry points

| Component | Commands to review/run | Interpretation |
|---|---|---|
| Android | `bash android/tests/run_phase24_tests.sh` and `bash android/tests/run_phase25_tests.sh` | Host contract tests; check script prerequisites first |
| Firmware | `bash firmware/tests/run_phase23_tests.sh` and `bash firmware/tests/run_stability_tests.sh` | Host/model/protocol tests; inspect scripts and prerequisites |
| Browser | `node firmware/tests/test-protocol.cjs` | Canonical root `web/` JS protocol test; see test file for assumptions |
| Firmware build | `cd firmware && idf.py build` | Requires ESP-IDF/NimBLE setup, does not flash |
| Android build | Android Studio build | SDK 36 and compatible Gradle setup required; wrapper absent |
| Hardware | Paired ESP32 nodes + Android/BLE + browser | Test manually and record devices, firmware versions, RF settings and outcomes |

The GitHub workflow runs safe metadata tests and selected existing host tests only; it does **not** claim complete firmware, Android or RF hardware validation.

## Manual hardware checklist

- Record device type, firmware tag, Android build, browser version and RF environment.
- Confirm Bluetooth picker selects the correct BLE device/address and logs real GATT outcomes.
- Confirm connected + authenticated status and message exchange both directions.
- Check text, photo and voice exchange, receipts, reconnection and background behavior without wiping data.
- Validate radio stability after PHY changes and app update path while retaining NVS/encryption key.
- Save sanitized logs; explicitly mark any skipped scenario.

Do not publish device secrets, pairing materials or private MAC addresses in test reports.

## Browser single-source regression

`python3 scripts/repository_check.py` rejects a second `firmware/web/` copy and stale v1.6.1 HTML asset references. Browser code should be edited only under `web/`; cross-client tests from other components must reference it directly. This protects against the v2.5.6 reconstruction error described in [REPOSITORY_REPAIR.md](REPOSITORY_REPAIR.md).

## Current vs. historical tests

`android/tests/run_current_tests.sh` (needs `kotlinc`, `java`, `python3`, `node`) and `firmware/tests/run_stability_tests.sh` are the gates for the current v2.5.6 source.
Older `run_phase2x_tests.sh` / `test_phase2x_*.py` / `test_ble_209_preservation.py` files are **frozen historical snapshot gates**: they pin old UI strings, old file hashes, or reference ZIPs that are not in this repository, so some are expected to fail on `main`. Do not treat them as regressions.

## Gradle wrapper

`android/gradlew` and `android/gradlew.bat` pin Gradle 8.14.4. A build also needs the Android SDK (platform 36) and JDK 17: `cd android && ./gradlew assembleDebug`. This has not been run in CI.

## Replay protection (EDIT messages)

Incoming EDIT envelopes older than the last applied edit are ignored (Android `MessageMutation.apply(..., sentAtMs)`, web `app.js`). The edit timestamp is persisted in history (Android history format v3, still reads v1/v2). This does not stop replay of other message kinds; see SECURITY.md.
