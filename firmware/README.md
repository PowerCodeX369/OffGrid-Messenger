# OffGrid Messenger Phase 1.4.8 — USB Output Performance Candidate

**Base:** experimental Phase 1.4.7 (12 Mbps adaptive unicast). **Frozen rollback:** Phase 1.4.5.2 WatchdogFix. Neither original is modified.

## What the screenshot demonstrated

Two nodes at −33/−25 dBm using an actual **12 Mbps PHY** still delivered an encrypted 45.7 KB photo at only ~1.1 KB/s. Increasing the PHY by itself cannot improve the browser ↔ USB ↔ ESP-NOW ↔ USB ↔ browser acknowledgement cycle.

## Exactly what changed

Only `main/main.c` changed, plus this README and one regression test. Every `web/` file and the encryption/fragment format are **identical** to Phase 1.4.7.

- Replaced hundreds of `putchar()` calls for each HEX payload with one bounded, complete `fwrite()` record: `RX|...|HEX` and `QUEUED|...|HEX` still have *exactly* the same format.
- Changed `stdout` from `_IONBF` (unbuffered) to `_IOLBF` (line buffered), flushing each newline-terminated record as one buffered operation.
- Preserved the existing 1/6/12 Mbps peer-adaptive PHY, send scheduling, ACK and retry semantics, NVS key storage, Web Crypto encryption in browser, 500-character messaging, photos, UI, galleries, and FreeRTOS watchdog fix.

**Why:** the old `print_hex()` wrote 320 hexadecimal characters separately for a full 160-byte fragment. This may be a substantial component of the observed ~440 ms round trip over USB Serial/JTAG. This release tests that hypothesis. It does **not** batch radio ACKs or remove app-level acknowledgments, and is not guaranteed to reach a particular KB/s without hardware testing.

## Install

1. Keep a copy of your working 1.4.7 firmware and the known-good 1.4.5.2 ZIP.
2. In VS Code with ESP-IDF 5.2+, set target `esp32c3`, build and **flash both boards** (normal flash, not erase, to retain NVS keys).
3. Close old browser tabs and stop the previous local HTTP server.
4. From this project folder run:
   ```sh
   python3 -m http.server 8765 --directory web
   ```
5. Open `http://localhost:8765/?v=1.4.8` in two Chromium tabs; hard refresh and connect each board.

## Acceptance tests

1. Test 10 encrypted texts in both directions and receipt ticks first. Ensure no `task_wdt` warnings and no missing/garbled RX/QUEUED serial lines.
2. Verify PHY still rises to 12 Mbps in Auto under strong signal.
3. Send the *same 45.7 KB photo* at the same distance. Compare **KB/s and ACK ms** against 1.4.7, which measured about 1.1 KB/s / 440 ms.
4. Confirm the image finishes, decrypts, and displays on the other browser; gallery still works.
5. If speed does not improve, preserve the 1.4.7 result and collect diagnostics. The next meaningful optimization would be selectively batched fragment acknowledgments while retaining full-photo browser confirmation, a more invasive protocol update requiring separate testing.

## Local checks

```sh
node --check web/app.js
node tests/test-protocol.cjs
node tests/test-adaptive.cjs
python3 tests/test_usb_records.py
python3 tests/test-watchdog-fix.py
```

**Testing limits:** Host wire-format tests do not substitute for compiling firmware with ESP-IDF or measuring your ESP32-C3 boards. Do not treat this as the new stable baseline until it passes hardware acceptance tests.
