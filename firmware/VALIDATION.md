# Phase 1.5.1 validation record

**Source**: byte-for-byte firmware copy of working Phase 1.5 (`main/` unchanged), originally based on official stable Phase 1.4.11. No ManualLR / RFTest code included. The `web/` source adds the 120-second recording limit and authenticated `played` receipt type.

## Automated tests

- Node WebCrypto AES-256-GCM: 12/105/120-second voice durations (including >65535-ms metadata), 32 KiB/250000 B/384 KiB payloads across three codecs, chunk reassembly, tamper rejection, correct played receipt type, and compatibility with original 30-second Phase 1.5 notes.
- Headless Chromium at 1100 px and 390 px: start/stop/cancel capture (mock MediaRecorder), 120-second simulated recording acceptance, `playing` event (not `play`) triggers local blue mic and one receipt, sender preview cannot falsely mark remote play, authenticated-style receipt marks sender icon, state persisted in encrypted record payload, old ticks unaffected, and no browser exceptions.
- Original 1.5 firmware, AES, photo, auto-scroll, radio rate, USB, watchdog, and selective-ACK regression suite.
- ZIP integrity and SHA-256 comparison of unchanged firmware against 1.5.

## Limitations

Automated browser events are simulated. **No actual ESP32 radio, physical microphone, 2-minute real-Opus capture, or ESP-IDF compiler is available in this runtime.** An encoded voice note exceeding 384 KiB is rejected rather than truncated. `played` signifies audio playback started; it does not prove the entire recording was listened to. Both web tabs must run Phase 1.5.1 to exchange the new receipt.

## API documentation

- Media `playing` event: https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/playing_event
- Media `play` event: https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/play_event
- MediaRecorder capture: https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/start
- ESP-NOW application-level receipts: https://docs.espressif.com/projects/esp-idf/en/latest/esp32c3/api-reference/network/esp_now.html
