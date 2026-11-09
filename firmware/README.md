# OffGrid Messenger — Phase 1.5.1 · Two-minute voice + played receipts

**Parent: working Phase 1.5 ReliableLR + Voice, which itself was built directly from the official Phase 1.4.11 LongRange stable baseline.** Phase 1.4.11.1 ManualLR and 1.4.11.2 RFTest are test-only and are NOT in this release. This is a separate update candidate; **Phase 1.4.11 stays the user-designated official stable baseline** until explicitly promoted.

**Firmware is identical, byte-for-byte, to working Phase 1.5. You do NOT need to flash either ESP32 for this update.** Update `web/` on BOTH Chromium tabs; keep the existing Phase 1.5 firmware and NVS pairing keys. The ZIP includes unchanged full firmware for completeness.

Two ESP32-C3 Super Minis connected to two Chromium tabs on one laptop. Native USB Serial/JTAG, Wi-Fi ESP-NOW, channel 6, AES-256-GCM browser encryption, ESP32 NVS pairing keys. No router, Wi-Fi access point, internet, GPS, LoRa, Meshtastic, third-party services, or new hardware.

## What's new

### A. Two-way-verified adaptive PHY and safe Long Range recovery

**The essential fix:** RSSI is *real*, but **RSSI alone cannot prove that a packet/ACK returns to the sender**. Phase 1.5 uses unicast ESP-NOW **application-layer PING/PONG** (internal `TYPE_HELLO` with bounded IDs and a matched 32-bit session token), plus existing acknowledged chat/data/benchmark packets. No browser, USB or pairing-key dependency for liveness.

- A sender with no outstanding transfer sends a short unicast PING about every 2.4 seconds. Its recipient replies with a PONG at its currently configured peer PHY.
- A matched PONG is proof of a completed **bidirectional** radio exchange. Stale, unmatched or forged ID-only replays aren't treated as current confirmations. **Radio probes are not cryptographically authenticated, unlike encrypted chat**; MAC/session matching only detects obvious stale packets and off-peer traffic.
- If a PONG is not returned within 1.8 seconds, AUTO/SPEED downgrade the current rate **one step**: 12 → 6 → 1 Mbps → LR500 → LR250, with a minimum interval between rate changes. The photo browser's missing acknowledgments can also cause a downgrade, but many simultaneously failing photo chunks cannot instantly skip five PHY rates.
- Peer disappears: its MAC remains stored in the existing `offgrid` NVS namespace. AUTO attempts LR250 peer unicast rather than relying on short-range-only 1 Mbps broadcast.
- **Promotion is intentionally slow:** require at least three recent, confirmed two-way deliveries, fresh *measured* RSSI, no pending chat packets, a dwell interval, and an upgrade cooldown. Upgrade at most **one PHY step**. Never promote simply because broadcast RSSI looks good. A failed recent promotion results in 45-second retry suppression to prevent flapping at a marginal boundary.
- Explicit manual LR500/LR250 settings remain available in Settings as in the **1.4.11 stable baseline**. AUTO will not override an explicitly forced mode. **RF Lab and transmit-power experiments are NOT included.**
- `LINK|...` telemetry adds `HEALTHGOOD`, `PINGOK`, `PINGLOST`; accessible from **Chat information** and the link badge tooltip. These are link-level reports, not authenticated/encrypted user messages.
- No GPS or distance inference. No automatic rate can guarantee a specific range without field testing.

### B. Encrypted voice notes

- Tap **microphone** in the composer to record; tap again to stop and send, or press **Esc** to discard. Hard limit: **120 seconds (2 minutes)**; encoded audio limit: **384 KiB**. A recording that exceeds the encoded limit is rejected locally with an explanation; the requested 12 Kbps encoder bitrate is not guaranteed by the browser.
- Uses Chromium `MediaRecorder`, preferring `audio/webm;codecs=opus` with an audio bitrate request of 12 Kbps (the browser may use a different bitrate). Alternative audio/ogg and audio/mp4 if locally supported. If unsupported, record is disabled with a clear error; no insecure codec guessing.
- The microphone is **on the laptop/browser**. ESP32 doesn't need microphone, speaker, storage, or audio libraries.
- Encoded bytes are placed into the **existing encrypted AES-256-GCM envelope**, with voice kind `7`, codec tag, and millisecond duration split across existing two 16-bit metadata slots (low 16 bits `width`, high 16 bits `height`). Notes encoded by Phase 1.5 remain compatible. Same 160-byte fragmented transport, selective ACK, retries, blue ticks and encrypted IndexedDB storage as photos. The same shared 256-bit key stays in NVS and in browser memory only.
- Receiver authenticates the complete GCM payload before it is stored/displayed. Inline `<audio controls>` never auto-plays. Recordings are reclaimed from memory when closing/rebuilding the chat.
- Voice has the same total-transfer limitation as photos. A recording larger than 384 KiB is rejected locally; it is not sent partly.

### C. Played receipts, distinct from read ticks (new in 1.5.1)

- A **gray microphone** beside each voice note means **not yet played**. A **blue microphone** means audio playback has begun.
- On the **receiving browser**, the microphone turns blue on the `<audio>` element's **`playing` event**, not on chat view/read, not on a mere `play` request and not on media download. It triggers at the beginning of playback, not necessarily after the entire note has finished.
- The receiver sends a **new AES-256-GCM-authenticated `played` control envelope** (`kind=8`, containing the original 8-byte message ID) using the unchanged 160-byte ESP-NOW fragment transport. Only that receipt makes the microphone **blue on the sender**. Merely listening to your **own sent recording** does not claim the peer listened.
- The existing blue **double ticks** still indicate **chat read**, independently of whether a voice note was played. A voice note can have blue ticks and a gray mic until playback occurs.
- The `played` state is stored in encrypted IndexedDB history; if a played receipt cannot be delivered, the receiving browser retries it after next successful connection. Duplicated playback/receipts do not reset ticks or create multiple chat messages.
- Both browser tabs MUST use 1.5.1 for the new played receipt to be understood. 1.5.1 firmware is unchanged from 1.5.

## Installing and testing (both boards)

1. Preserve the **original Phase 1.4.11 ZIP**. Extract this 1.5 experimental folder separately.
2. **For this version, do not rebuild or flash firmware:** Phase 1.5.1 does not alter firmware. If you're already sending voice messages successfully with Phase 1.5, keep that firmware running on both boards. If starting from older hardware firmware, use the included Phase 1.5 firmware and an ESP-IDF **5.2+** toolchain, flash both without erasing NVS. A full ESP-IDF compile is unavailable in this environment.
3. Ensure both browsers load the updated 1.5.1 `web/` files; older 1.5 browsers don't understand the `played` receipt or voice notes exceeding 30 seconds.
4. From the extracted root:

   ```bash
   python3 -m http.server 8765 --directory web
   ```

   Open **http://localhost:8765/?v=1.5.1** in two Chromium tabs, one USB port per tab, and hard-refresh both. Microphone access requires `localhost` or HTTPS (secure context).
5. **Messaging regression:** send five texts in both directions; verify delivered and read ticks. Send one photo and verify its gallery and progress. Run the existing USB/radio/encrypted diagnostics.
6. **Voice:** grant microphone permission in the sending browser. Record 3–5 seconds, stop, wait for delivered ticks; listen on other tab using the audio controls. Repeat in both directions. Test one 110–120 second recording; if your browser ignores the requested codec bitrate, the 384 KiB size check may reject it rather than send a truncated note. Retry failed notes by clicking the bubble. Test Esc cancel and reconnect.
7. **Played receipt:** Open the chat on the receiving browser without playing the voice note (blue read ticks should NOT turn the microphone blue). Press Play and confirm the receiver's microphone goes blue; after the authenticated receipt crosses ESP-NOW, confirm the sender's goes blue too. Play your own outgoing note and verify this **does not** falsely mark it as played by the peer. Refresh the tabs and check that the played state persists in encrypted history.
8. **Automatic PHY:** set both Settings → Transfer profile to **Auto**. Watch `LINK` values. At short distances, 3+ successful peer PONGs and real RSSI may upgrade 1→6→12 Mbps. On link loss the rate descends toward LR250. Use the connection dialog tooltip/info to see PINGOK/PINGLOST, and check a failing link doesn't bounce repeatedly between fast rates.
8. If any issue, reflash **the frozen Phase 1.4.11 baseline**. No RF Lab experimental code is required for this phase.

## Known limits and honesty about verification

- **Host-side syntax and browser simulations do not replace a real ESP-IDF build** or two-board transmission. We can test the policy and crypto locally but cannot guarantee real-world fallback/recovery, microphone compatibility or throughput until your hardware tests.
- `esp_now_send()` success means an ESP-NOW packet was queued; the browser delivery status needs its own acknowledgement. Our new PONG provides an additional, application-level radio health signal, not proof of AES authentication.
- The fallback is deterministic but cannot 'measure' RSSI after the peer disappears; it uses missing confirmations at that point. RF path asymmetry and the other board's configured PHY can delay reconnection.
- 12/6/1 Mbps and 500/250 Kbps are **PHY choices**, not promised application data rates; quality and throughput still depend on overhead, USB, ACKs and environment.
- Browser recording bitrate is a *request*, not a guarantee. The 384 KiB and 120-second limits are enforced.
- Manual LR and automatic LR can be tested with both boards close together, but actual range and weak-signal sensitivity require field/attenuation tests.

## Official API references

- Espressif ESP32-C3 ESP-NOW guide: https://docs.espressif.com/projects/esp-idf/en/latest/esp32c3/api-reference/network/esp_now.html
- Espressif ESP32-C3 Wi-Fi LR protocol guide: https://docs.espressif.com/projects/esp-idf/en/latest/esp32c3/api-guides/wifi-driver/overview.html
- MDN MediaRecorder: https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder
- MDN getUserMedia: https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia

### Local tests

```bash
node tests/test-voice.cjs
python tests/test-voice120-browser.py
python tests/test-played-receipts-browser.py
node tests/test-protocol.cjs
node tests/test-selective-ack.cjs
node tests/test-adaptive.cjs
gcc -std=c11 -Wall -Wextra -Werror -I main tests/test-link-health.c -o /tmp/link-health && /tmp/link-health
python tests/test-voice-browser.py
python tests/test-auto-scroll-browser.py
python tests/test-selective-ack-firmware.py
python tests/test-long-range-contract.py
```

`tests/smoke_browser.py` uses real loopback navigation and requires a browser environment that permits localhost (blocked by this isolated CI environment). The voice UI test runs locally with a **simulated microphone** and makes no claims of physical hardware validation.
