# OffGrid Messenger — Phase 1.4.9 Diagnostics

**Purpose:** Measure, before changing the working photo transfer protocol, where its ~1.1 KB/s throughput bottleneck lies.

## Baselines and safety

- **Frozen recovery baseline:** `OffGridMessenger_Phase1_4_5_2_WatchdogFix.zip` (unchanged; keep a copy).
- **Direct parent:** Phase 1.4.8 USBOutput. This release retains its adaptive 1 / 6 / 12 Mbps PHY, NVS pairing key, 500-character text, Web Crypto AES-256-GCM, photo gallery, existing chat ACK/retry format, and watchdog task fix.
- This is a **diagnostic build**, NOT a photo-speed fix. It does not use batched photo acknowledgments yet. The benchmark radio frames are used exclusively for tests.
- Both ESP32-C3 boards must be flashed with the Phase 1.4.9 firmware. Otherwise USB diagnostic commands and radio-only packets will not work.

## Installation

1. Extract ZIP and open the containing directory as an ESP-IDF project in VS Code.
2. ESP-IDF target: `esp32c3`. Flash **both** boards using ordinary Flash, **not** Erase Flash; this preserves their NVS pairing keys.
3. Quit other serial monitors; connect both boards by native USB.
4. From this project directory run:

   ```bash
   python3 -m http.server 8765 --directory web
   ```

5. Open two Chromium tabs at `http://localhost:8765/?v=1.4.9`, hard-refresh, and connect **one distinct board per tab**. Use the same `localhost` address/port as your earlier version to preserve browser-stored encrypted chat history. The AES key itself is loaded into RAM from ESP32 NVS over local USB.
6. Confirm normal **encrypted Hello** messages work both ways **before** benchmarking.
7. Open **Settings (gear) → Speed diagnostics → Run all tests** in only the sender's tab. Keep the other tab connected with the same AES key. Do not send chats during the benchmark.
8. Click **Copy results** and paste them into ChatGPT. Repeat from the other side if comparing directions.

## What the four tests mean

1. **USB PC → ESP32:** 96 × 160-byte hex records written to the device; firmware checks the bytes and returns one summary. No ESP-NOW or browser ACK per packet. Includes console command parsing and USB serial transfer, not just PHY USB line rate.
2. **USB ESP32 → PC:** firmware streams 96 × 160-byte hex records and a final summary. No radio involved. Measures browser-visible USB receive speed.
3. **ESP-NOW radio only:** 32 × 160-byte data packets sent by the sender ESP32. The remote ESP32 sends an MCU-level ACK for each. **No browser involvement on either side during this test.** The data PHY may use the current adaptive 1/6/12 Mbps peer rate; acknowledgments stay at low-rate broadcast. The measured value includes conservative per-packet scheduler delays and MCU ACKs; it is **not** the raw PHY bitrate.
4. **Full encrypted path:** 8 KiB of random diagnostic data encrypted using the existing AES-256-GCM browser key, segmented and transmitted over the same normal fragment/APPACK route used by photos. The remote browser authenticates the complete payload and responds with an encrypted final acknowledgment. Neither test payload nor ACK is stored in chat history.

The diagnostic results show KiB/s, elapsed milliseconds, direct-radio packet losses/retries, ACK round trips, and a suggested next investigation. None of these results are fabricated or inferred from RSSI.

## Interpretation

- USB tests much faster than radio only: investigate radio send/receive scheduling and retransmissions.
- Radio-only fast but full encrypted path slow: investigate the per-fragment browser ACK path, Web Serial framing, browser scheduling and batching acknowledgments.
- USB slower than radio-only: investigate native USB serial console handling.
- The 12 Mbps PHY rate alone does **not** imply 12 Mbps file throughput.

## Protocol changes (diagnostic only)

Additional newline-delimited USB commands:

- `USBIN_BEGIN <token>` / `USBIN <token> <seq> <160 bytes hex>` / `USBIN_END <token>` → `USBIN_DONE|token|count|bytes|duration_ms`.
- `USBOUT <token> <count>` → `USBOUT|token|seq|hex` lines, followed by `USBOUT_DONE|token|count|bytes|duration_ms`.
- `RBENCH <token> <count>` → `RBENCH_START`, then `RBENCH_DONE|token|count|ok|lost|retries|bytes|duration_ms|avg_ack_ms|phy|rssi`.

ESP-NOW frame types 5 and 6 are strictly diagnostic. **Types 1–4, the existing message format, ACKs, keys, and chat logic are unchanged.** `READY` includes `DIAG=1` in addition to its original fields. Browser app envelope adds experimental type 5 (probe) and type 6 (probe acknowledgment); normal envelope types 1–4 are unchanged.

## Verification performed in this build environment

- `node --check web/app.js` and `node --check web/protocol.js`
- `node tests/test-diagnostic-protocol.cjs` (encrypted probe reassembly and tamper rejection)
- `python tests/test-diagnostic-contract.py` (firmware/client contract, existing watchdog/key/rate paths)
- Existing protocol and adaptive-controller tests
- C syntax-only check with host-type stubs (not an ESP-IDF compile)
- Dialog render smoke test (local page navigation blocked by browser policy; loaded HTML/scripts directly for UI check)

**Not performed:** actual ESP-IDF build, physical board testing, USB throughput measurements, or real radio throughput measurements. If firmware compilation fails, send the actual error output; do not assume the build is validated by these tests.

## Rollback

Keep both the original frozen 1.4.5.2 ZIP and the working 1.4.8 ZIP. If the diagnostic release destabilizes chatting, stop testing, flash both boards back to the known-good firmware, and restore its bundled web app. Do not use Erase Flash unless you intend to erase stored pairing keys.
