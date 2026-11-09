# OffGrid Messenger — Phase 1.4.10.4 Auto-scroll (web-only patch to experimental Selective ACK)

**Authoritative frozen baseline: Phase 1.4.10.2_InterruptUSB.** This new release is a *separate, reversible experiment*, not a replacement for that measured-working version.

## Phase 1.4.10.4 changes — only Web UI

- **Incoming and outgoing messages automatically scroll to the latest bubble** on arrival.
- **Incoming photos stay in view after decoding/rendering** using a `ResizeObserver` and image-load event.
- **Restoring chat history** and **opening a chat on a narrow/mobile screen** scroll to its latest message.
- When the reader intentionally scrolls up through history, automatic scrolling caused by *layout resize alone* pauses. A genuinely new message resumes auto-follow.
- The approved visual design, encryption, stored keys, original message protocol, photo transfer protocol, diagnostics, and ESP32 firmware are unchanged.
- **GPS/location sharing has been removed from the project scope and future roadmap**, per user request.
- **Next planned radio phase:** ESP-NOW Long Range (LR), as a separate hardware-tested experiment. See [ROADMAP.md](ROADMAP.md) for the revised plan with GPS removed. Actual outdoor range is not yet established.

### Install without reflashing

Keep both ESP32 boards on the **Phase 1.4.10.3 firmware** if you are testing Selective ACK; or retain the frozen **Phase 1.4.10.2 firmware** with Legacy photo receipt mode.

Run `python3 -m http.server 8765 --directory web` from this project directory. Open `http://localhost:8765/?v=1.4.10.4` in both tabs and hard-refresh. No changes to NVS, pairing, or the radio firmware are required for auto-scroll.

### Selective ACK performance observation

Observed real photo speed improved from **7.2 KiB/s (1.4.10.2)** to **7.7 KiB/s (1.4.10.3)**, only about 7%. The selective ACK implementation is still experimental and is **not promoted to stable**. Keep the frozen 1.4.10.2 rollback separately.

## Why this version exists

On two ESP32-C3 Super Minis, Phase 1.4.10.2 was measured at **215.83 KiB/s USB upload**, **42.16 KiB/s USB download**, **15.15 KiB/s ESP-NOW-only**, **4.11 KiB/s encrypted browser-to-browser** with 37 ms fragment ACKs, and about **7.2 KiB/s actual photo transfer**. The radio was running up to 12 Mbps PHY.

Espressif notes that the MAC send callback does not prove the application received data, and recommends application acknowledgments, sequence numbers, and retransmissions. See the ESP32-C3 ESP-NOW documentation: <https://docs.espressif.com/projects/esp-idf/en/latest/esp32c3/api-reference/network/esp_now.html>.

We therefore reduce acknowledgment overhead for *photos only*, without weakening authenticated whole-message delivery.

## What is new

- Each encrypted photo uses an optional new **OGB5** fragment marker, with exactly the same 20-byte frame header and up to 160 ciphertext bytes as the existing **OGC4** frames. This flag only identifies a bulk transfer; the photo remains encrypted in the browser with AES-256-GCM. It does not reveal photo contents.
- The receiving browser groups up to **six received radio packet IDs** (not necessarily consecutive) from the same sender MAC and boot session. It sends a single `APPACKB` USB command, flushing a partial group after **75 ms**.
- The receiving ESP32 sends one low-rate (1 Mbps) `TYPE_BATCH_RECEIPT` ESP-NOW packet containing those specific packet IDs. The sending ESP32 reports `DELIVERED` for each included ID, and the browser can advance its photo upload window.
- Up to **six fragments can be in flight** on strong links, staying below the existing firmware limit of eight pending messages. Weak links use a smaller window.
- Missing batch ACK: the sender's **existing 850 ms firmware retry** resends outstanding fragments. The receiver's original duplicate-processing path issues a single legacy ACK for duplicates, so lost batch receipts are recoverable. The normal three-attempt browser retransmission and progress/status handling remain in place.
- Text, delivery/read receipts, pairing, NVS keys, AES-256-GCM, USB interrupt-driven driver, watchdog fix, adaptive PHY, image compression and gallery are otherwise unchanged.
- Settings now includes **Photo receipt protocol**: `Selective batch ACK` or `Legacy` (immediate fallback for new photo transfers). Disabling automatic photo throughput also restores legacy single-fragment behavior.
- Speed diagnostics now compare **two encrypted 8 KiB tests**: legacy versus selective ACK, while preserving the original USB and ESP-NOW-only tests.

**Status integrity:** A fragment ACK only means the receiving browser accepted a structurally valid encrypted fragment. The sender's *chat* double-grey tick still requires the final encrypted, authenticated, receiver-generated `delivered` message after complete reassembly. Read receipts are unchanged. Radio acknowledgment frames themselves are not end-to-end authenticated; final delivery is.

## Install carefully

1. Save a copy of your **Phase 1.4.10.2_InterruptUSB.zip**. Do not overwrite it.
2. Open this Phase 1.4.10.3 project in VS Code + ESP-IDF 5.2+ and select the `esp32c3` target.
3. Build the firmware. **A complete ESP-IDF build has not been performed by the project authoring environment**; fix any local build error before flashing.
4. Flash **both** ESP32-C3 Super Minis using normal `flash` — **not `erase-flash`**. The NVS pairing keys must be preserved.
5. Stop any ESP-IDF serial monitors, then start the web UI from this project directory:

   ```bash
   python3 -m http.server 8765 --directory web
   ```

6. Open `http://localhost:8765/?v=1.4.10.4` in **both tabs**, hard-refresh Ctrl+Shift+R, and connect one ESP32 per tab. Keep the same localhost origin and port to preserve existing encrypted chat history.
7. `READY` must include `PROTO=2|CAP=180|...|DIAG=1|SACK=1`. Check both connections before testing photos.

## Test checklist

1. **Ordinary encrypted text**: send 10 messages in each direction; verify immediate two-way delivery and read receipts. This uses the unchanged original protocol.
2. **Legacy photo**: Settings → Photo receipt protocol → Legacy. Send a known photo and record KB/s and actual duration. Make sure the receiver sees the photo and that Delivered ticks appear only when it arrives.
3. **Selective photo**: Change setting to Selective batch ACK on the **sending browser**. Send the same source photo and compare time, transfer KB/s, output size, success and image integrity.
4. **Range test**: Increase physical separation, watch PHY/RSSI, and confirm retries recover missing packets. Verify that a lost/incomplete photo is *never* marked Delivered.
5. **Speed diagnostics**: Run all tests in one tab with the other connected; new output includes `Encrypted browser-to-browser (legacy)` and `(selective ACK)` and the measured speedup ratio. The remote browser must also run this release.
6. **Instant fallback**: Select Legacy again to revert all *new* photo transfers to the proven per-fragment protocol, without reflashing. If text/USB/radio regression occurs, flash both nodes back to the frozen **1.4.10.2 firmware**.

### Important compatibility detail

Old web browsers do not understand the `OGB5` bulk-frame marker, and old firmware does not understand `APPACKB`. **Both boards and both browser clients must be upgraded** for the fast photo mode. Legacy text is unchanged. Use Legacy photo mode for mixed-version testing.

## Testing performed here

- JavaScript syntax checks and the existing AES-GCM/500-char/photo protocol/regression suites.
- Real Web Crypto AES-GCM: encrypted 45 KB simulated photo; 289 frames; randomized omissions/reordering; selective retransmission; exact image reconstruction; altered ciphertext rejected.
- C host compiler: actual ID parsing/packing helpers and command parser; overflow, negative ID, unknown MAC, too many IDs rejected.
- Full firmware host-stub syntax-only validation with both IRQ and legacy USB configurations.
- Actual browser-side ACK grouping function exercised in Node with a simulated Web Serial writer: six-ID flush, timeout flush, duplicate prevention, sender session separation.
- Source comparison confirms the original USB driver, adaptive-PHY policy, UI styling, key handling and watchdog fix were retained.
- **Not tested here:** full ESP-IDF build, physical ESP-NOW performance, real browser-to-browser operation, packet-loss behavior on hardware or a measured speedup. The environment blocks local Chromium navigation. Please share real diagnostics before promoting this version to stable.

## Next planned release — ESP-NOW-LR

Once this extension is demonstrated reliable, research and test the ESP32-C3's ESP-NOW Long Range mode **as a separate experimental firmware branch**, comparing range, latency and throughput against frozen 1.4.10.2. Do not mix an unverified LR change into this transfer-speed release.
