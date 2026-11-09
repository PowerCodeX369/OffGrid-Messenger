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
