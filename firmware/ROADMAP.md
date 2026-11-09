# OffGrid Messenger road map (updated November 2026)

## Version ownership

- **Phase 1.4.11 LongRange:** authoritative, user-approved, frozen **stable** baseline.
- Phase 1.4.11.1 ManualLR: **test-only**, never base future releases on this.
- Phase 1.4.11.2 RFTest: **test-only**, never base future releases on this.
- **Phase 1.5 ReliableLR_Voice: validated working by user for voice playback; branched from 1.4.11.** Don't promote to stable without user acceptance and hardware tests.

## Complete

- Encrypted text and photos, 500-character text messages, compressed photos, gallery, encrypted chat history and stored ESP32 NVS keys.
- WhatsApp-style delivery/read receipts, responsive web interface and chat auto-scroll.
- Native ESP32-C3 USB Serial/JTAG interrupt-driven transport (measured 215 KiB/s PC→ESP32 in Phase 1.4.10.2).
- ESP-NOW adaptive 1/6/12 Mbps and LR 500/250 Kbps, peer persistence and discovery (Phase 1.4.11).

## Phase 1.5 candidate under validation

- New two-way ping/pong rate adaptation, retransmit/recovery and conservative upgrades.
- Browser-recorded AES-256-GCM voice via MediaRecorder, originally 30 s / 80 KiB. **Phase 1.5.1 candidate extends to 120 s / 384 KiB and introduces authenticated played receipts with blue/gray mic icons.**
- No GPS, LoRa, Meshtastic, external server or cloud requirements.

## Next, after user approves Phase 1.5.1

- Phase 1.6: background notifications (as supported by Chromium), stability and reconnect behavior, live outdoor RF tests, transfer efficiency and bug fixes.
- Phase 2.0: native Android/Kotlin app using BLE to talk to the ESP32, after web+firmware are solid. Document device BLE limitations and data throughput before implementing.
