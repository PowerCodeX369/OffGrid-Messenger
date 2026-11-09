# OffGrid Messenger — updated development roadmap (November 2026)

**Frozen stable baseline:** Phase 1.4.10.2 — Interrupt-Driven USB Serial/JTAG.

**Current experimental line:** Phase 1.4.10.4 UI + Phase 1.4.11 LR firmware. This has not been physically verified and must not replace the frozen baseline.

| Stage | Feature | State |
| --- | --- | --- |
| 1.0–1.4 | ESP-NOW text, authenticated browser encryption, photo transfers, photo gallery, contact names, message history, send/delivered/read receipts | Built and tested through successive versions |
| 1.4.10.2 | Interrupt-driven USB input and adaptive 1/6/12 Mbps ESP-NOW PHY | Frozen, measured-working baseline |
| 1.4.10.3–1.4.10.4 | Selective photo acknowledgments and auto-scroll / late image loading | Experimental, selective ACK measured ~7.7 KiB/s vs ~7.2 KiB/s baseline |
| 1.4.11 | **ESP-NOW Long Range**, manual LR500/LR250 and automatic fallback using saved peer and LR discovery probes | Implemented experimentally; compile and real outdoor tests required |
| 1.5 | Encrypted, compressed short voice messages | Planned |
| 1.6 | Offline notifications, connection recovery, transfer polish and outdoor range/stability testing | Planned |
| 2.0 | Native Android application in Android Studio, reusing validated messaging protocol | Last |

**GPS/location sharing has been removed from scope.** No GPS module, phone location exchange, or map-location messaging feature is planned.

**Engineering rule:** Never overwrite the frozen baseline. Research supported ESP-IDF interfaces, test in a separate branch, and verify on actual ESP32-C3 Super Mini boards before promotion. Real-world range is not guaranteed; test line-of-sight performance instead of assuming 1 km.
