# OffGrid Android Phase 2.4 — premium presentation and secure local inbox

This update builds on hardware-tested Phase 2.3 binary BLE and contains **no firmware changes**.
All preserved source SHA hashes are checked by `tests/test_phase24_preservation.py`.

UI implementation is in `PremiumUi.kt`; encrypted local storage is in `HistoryCodec.kt` and
`EncryptedHistory.kt`; process-scoped BLE ownership is in `BleSessionHolder.kt`; notification
and opt-in foreground service handling is in `OffGridNotifications.kt` and
`OffGridConnectionService.kt`. The existing OGM4/BLE packet formats are unchanged.

See root `README_FIRST.md`, `RESEARCH_DESIGN.md`, and `VALIDATION_24.md` for instructions,
documentation, test results, and limitations.
