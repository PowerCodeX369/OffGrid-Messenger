# OffGrid Messenger — Phase 2.2: Fast Media / BLE Pipeline

**The hardware-tested Phase 2.1 remains the rollback baseline.**

Phase 2.2 improves Android photo/voice transfer latency without changing any ESP32 firmware, the trusted 2.0.9 BLE GATT client, the encrypted OGM4 wire format, pairing keys, read receipts, or the approved desktop web code.

## What is optimized

1. **Windowed encrypted media transport:** up to THREE radio fragments are outstanding; each still requires a matched `QUEUED` and `DELIVERED` record before being counted complete. Unlike stop-and-wait, the host does not idle while previous fragments are awaiting acknowledgment. Bounded retries and out-of-order ACK matching are implemented.
2. **Urgent receipts preempt media:** the media scheduler stops submitting new frames, drains outstanding frames, sends queued delivery/read/played control envelopes, then resumes from its exact saved position.
3. **Incoming Web media OGB5 selective ACK batching:** Android groups up to four confirmed radio IDs per `APPACKB`, using an existing firmware command, and flushes after 65 ms. Text/OGC4 frames keep immediate legacy `APPACK`.
4. **Fast photos:** enabled by default in the top-right overflow menu. Image encoding targets 28 KiB (instead of 48 KiB). When a detailed image cannot reach 28 KiB, it falls back to a valid 48-KiB image rather than failing. Set Fast photos OFF to prioritize image quality.
5. **Measured speed indicator:** the photo/voice bubble displays percent and rolling average KiB/s during transfer. The BLE diagnostics also show completion elapsed time.

The original 2.1 voice codec and 16-kbit/s bitrate are unchanged; this release improves voice scheduling, not sound compression.

## Installation / upgrade

1. Download `OffGrid_Android_Phase2_2_FastMedia.zip` for the easiest upgrade; extract it and open its **`android`** folder in Android Studio.
2. Sync project (Android SDK 36, JDK 17, Gradle 8.14.4). Run on the already paired phone via wireless debugging, **without uninstalling the current app**. App package name remains `com.offgrid.messenger`, versionCode 220.
3. Connect to the same ESP32. First send a short TEXT: instant send/ACK must remain unchanged.
4. Send a 5-10s voice note and a small photo Android→web, then web→Android. Compare with your hardware-tested 2.1. The outgoing media bubble now shows KiB/s while active.
5. Open top-right ⋮ → **Fast photos**. Toggle OFF to use previous 48-KiB compression target for high detail.

**Do not reflash either ESP32.** The complete ZIP includes the exact known-working firmware and web only as source/rollback references. If media transfers become unreliable, simply reinstall/Run the Phase 2.1 Android project; the hardware is unchanged.

## Realistic performance limitations

- Current BLE firmware uses ASCII hexadecimal commands over GATT and ESP-NOW fragments of 160 encrypted bytes, so very large media still require hundreds of radio packets and GATT writes. Android's 3-fragment window reduces idle time but does not remove protocol/PHY overhead.
- ESP32-C3 BLE and ESP-NOW share one 2.4 GHz radio. Radio coexistence, connection intervals, retries, and the phone can limit real throughput.
- Synthetic 36-frame RTT model (120 ms per ACK): old stop-and-wait 4320 ms; bounded window 1440 ms. **NOT a measured hardware result and not a promised speedup.** Your device's KiB/s display is the real metric.
- Only app foreground / in-memory messages; no always-on BLE background service or persistent encrypted history added yet.

## Verification and reproducibility

- `cd android && bash tests/run_phase21_tests.sh` — existing encrypted media interoperability, pairing/transport preservation, BLE contracts.
- `cd android && bash tests/run_phase22_tests.sh` — new window, batching, failure/pause/resume, scheduling model, source preservation.
- Firmware ESP-NOW/USB and browser source are unchanged byte-for-byte; no SDK firmware build is needed.
- Kotlin transport & media helpers compile under local Android API stubs. **No full Android Gradle build was possible in this environment**, so the phone update still needs testing before being called hardware verified.
