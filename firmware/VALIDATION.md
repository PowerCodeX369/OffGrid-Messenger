# Phase 1.5 validation record

**Origin:** exact copy of 1.4.11 LongRange. 1.4.11.1/1.4.11.2 test-only branches not included. The frozen 1.4.11 source files were not modified.

**Verified in container:**

- Inspected current official Espressif ESP-NOW and Wi-Fi LR documentation and current MDN MediaRecorder docs.
- Host-side GCC C syntax checked with provided ESP-IDF stubs in both IRQ and legacy USB configurations. These stubs are NOT full ESP-IDF.
- Pure C link-policy tests: RSSI alone not sufficient for promotion, 3 confirmations needed, freshness, cooldown and failed-upgrade hold, one-step upgrade, correct 12→6→1→500→250 fallback.
- Standalone AES-GCM voice encoding, audio data length, duration, 160-byte fragmentation/reassembly, wrong-tag rejection, and three allowed audio containers.
- Existing encryption, photo selective-ACK, USB output and IDF watchdog regression tests.
- Real headless Chromium interface with a **mock MediaRecorder** at 1100 px and 390 px: start, stop, microphone release, voice preview/audio controls and queued message. Real microphone was not available for testing.
- Existing Chromium auto-scroll test passed.

**Not verified:** real 5.x ESP-IDF compilation, hardware LR fallback and recovery, real microphone codec interoperability, two-board voice sending throughput, actual open-field range, maximum voice bitrate.

**Do not treat simulated policy tests as proof that any particular physical distance will work.**
