# OffGrid Messenger — Phase 1.4.3 Performance Fix

**Why photos were slow:** Phase 1.4.2 used 160 data bytes per chunk and a fixed **65 ms delay before every ESP-NOW packet**, including ACKs. A 64 KiB image required ~410 chunks and ~410 radio acknowledgments; delays alone consumed ~53 seconds, often much more with USB/browser processing. A reported 12 Mbps PHY rate does **not** mean 12 Mbps file throughput.

## Changes

- **Firmware (both boards MUST be reflashed):** ESP-NOW send callback completion pacing replaces the 65 ms fixed gap; 4 ms protective minimum gap, callbacks in a Wi-Fi task notify a semaphore without doing any expensive work. Wait up to 300 ms for a send completion. Event-queue polling 30 → 2 ms. `MAX_TEXT` raised from 180 to **228 bytes**, keeping full firmware frame at **250 bytes**, compatible with ESP-NOW v1 frames and v2-capable radios.
- **Web app:** encrypted app fragments 160 → **208 bytes**, leaving the 20-byte app header inside the 228-byte firmware payload. All encryption, photo gallery, local encrypted history, and the AES-256-GCM packet format remain unchanged. No storage migration or new pairing key needed.
- **Compatibility check:** `READY` line now contains `CAP=228` / `TXDONE=1`. Browser refuses older firmware. Both boards need 1.4.3.
- **Useful diagnostics:** current image transfer shows actual application payload KB/s, and last photo throughput appears in link details. The value is not RF PHY bitrate.

## How to install

1. Open this exact folder in VS Code with ESP-IDF 5.2+ and build/flash **both** ESP32-C3 Super Minis. The firmware is identical on both.
2. Close all prior web tabs and stop the old Python server. From this project root:

   ```bash
   python3 -m http.server 8765 --directory web
   ```

3. Open **http://localhost:8765/?v=1.4.3** in two Chromium tabs; hard-refresh each. Connect each board to a separate tab. `READY|...|CAP=228|TXDONE=1` must appear in the serial console if you inspect it, and UI should show a peer after a transmission.
4. Use the same localhost origin/port and browser profile to preserve your existing encrypted history and pairing keys.
5. Send text both directions, then a **5–10 KB image first**, then a 40–60 KB image. Check for delivered and blue ticks, gallery navigation, speed in KB/s, and success after disconnect/reconnect.
6. Test both **Auto** and **Range first**. Watch RSSI, TX PHY, Retry/Fail and actual KB/s. The rate adapts by signal quality, but the maximum physical range is still determined by hardware and environment.

## Important compatibility / reliability notes

- New browser JS cannot transmit photos to old 1.4.2 boards: new frames can be 228 bytes. **Flash both boards and run this exact web release.** Do not mix firmware/web versions.
- The ESP32 send callback confirms only MAC-level transmission, not recipient-browser delivery. The existing `APPACK`, app-level encrypted whole-message receipts, retries, and deduplication remain in place.
- `TXDONE` timing provides faster pacing without blasting unsynchronized ESP-NOW calls. If the radio cannot keep up, retries and fallback still apply. Do not assume the theoretical 12 Mbps PHY rate is achievable for files.
- **This code has not been built against the ESP-IDF toolchain or exercised on your real ESP32 radios here.** If you see build errors or packet loss, share the full compiler log and `LINK|...`, `RATE_ERROR|...` or `RADIO_ERROR|...` serial lines. Your previous 1.4.2 ZIP remains an immediate rollback option.

## Security

End-to-end AES-256-GCM on the browser remains unchanged; RF MAC addresses and timing metadata are still visible. This project has not undergone a security audit. Use the same confidential pairing key on both sides.

