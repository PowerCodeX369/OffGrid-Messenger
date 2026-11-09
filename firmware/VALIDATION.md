# Phase 1.4.11.1 — Local validation record

**Status:** Experimental web-only manual-LR control patch. The 1.4.11 LR firmware still requires real ESP-IDF build and radio confirmation.

Validated locally:

- Official Espressif documentation checked for `WIFI_PROTOCOL_LR`, `WIFI_PHY_MODE_LR`, `WIFI_PHY_RATE_LORA_250K`, `WIFI_PHY_RATE_LORA_500K`, `esp_now_set_peer_rate_config()` and ESP32-C3 compatibility.
- C source syntax checked against host-side stubs, both USB IRQ enabled/disabled, with format-string mismatch errors enabled. **Host stubs are not ESP-IDF**.
- Unit tests checked target PHY for strong/moderate/weak RSSI, manual 250/500 kbps, and 12 -> 6 -> 1 -> 500 -> 250 kbps fallback.
- Static contract tests checked native USB Serial/JTAG IRQ implementation, FreeRTOS task watchdog yield, NVS pairing-key storage, LR peer persistence/probes/response, unicast confirmations and existing browser encryption.
- JavaScript syntax checked with Node.js.
- Existing AES-256-GCM photo and text protocol tests passed, including 500 Unicode characters and a 96 KiB test payload, loss/reordering and wrong ciphertext rejection.
- Selective ACK, diagnostics, browser auto-scroll smoke, handshake and watchdog regression tests passed.
- The approved web CSS, logo, encryption protocol module and adaptive photo controller are byte-for-byte identical to Phase 1.4.10.4.

**Not validated here:** full ESP-IDF build, actual `esp_now_set_peer_rate_config()` success with LR phy/rate on device, control ACKs at distance, multi-minute radio stability, real throughput or claimed range.

**Do not promote this experimental build to the frozen baseline until the tests in README.md pass on both boards.**

## Phase 1.4.11.1 additional validation

- Isolated Chromium DOM test with simulated Web Serial confirmed emitted commands `RATE LR500`, `RATE LR250`, and `RATE AUTO` in sequence.
- Simulated `LINK` telemetry correctly displays confirmed 500/250 Kbps only when the firmware-reported MODE and PHY agree with the requested rate.
- The quick switch and the existing Settings selector stay in sync.
- UI showed no horizontal overflow at 1920, 850, 590 or 390 pixel widths in isolated Chromium rendering.
- Existing Node.js encrypted message / 96-KiB photo, selective ACK, diagnostics and adaptive photo tests passed.
- Firmware, rate policy, USB driver, cryptographic protocol, and project configuration remain byte-for-byte identical to experimental Phase 1.4.11.
- **Not validated:** connection to physical ESP32-C3 boards, successful per-peer PHY configuration in ESP-IDF, LR delivery and acknowledgments, outdoor distance, or real RF throughput.
