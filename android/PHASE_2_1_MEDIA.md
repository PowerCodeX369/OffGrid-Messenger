# OffGrid Messenger Phase 2.1 — Android encrypted photos and voice notes

**This is an Android-only feature release based on the fast Phase 2.0.9 radio/BLE baseline.**
No ESP32 firmware update is required. The `firmware/` folder in the complete archive is included for reference/rollback and is byte-identical to Phase 2.0.9.

## What's new
- Select photo with native Android gallery picker; bounded offline JPEG compression at <=48 KiB; EXIF/GPS metadata discarded by re-encoding. Receive and display authenticated JPEG/PNG/WebP photos from web.
- Tap microphone to start recording; tap stop to encrypt/send. Opus-in-Ogg on Android 10+ when supported; AAC-in-MP4 fallback on Android 8+. Recording ends automatically after 30 seconds; maximum encoded note size 96 KiB. Offline recording; temporary plaintext files are deleted after use.
- Play received voice notes by tapping play; `PLAYED` receipt sent only upon actual playback completion, not merely because a note was received. Existing delivered/read receipts remain separate.
- Receive encrypted `OGB5` selective-ACK media packets from the approved web app (previous Android parser only accepted `OGC4`). Android continues sending legacy `OGC4` one-ACK-per-fragment packets for compatibility with unchanged firmware.
- Media transfer progress percentage and small transfer limits; urgent delivered/read/played receipts can be interleaved at acknowledged fragment boundaries during large outgoing transfers.
- Existing text, pairing key, AES-GCM/OGM4 format, 2.0.9 BLE MTU negotiation + latency tuning, native Android app ID, and radio source preserved.

## Install on the same phone
1. Extract `OffGrid_Android_Phase2_1_Media.zip` and open its `android/` folder in Android Studio.
2. Sync Gradle and run on the **same phone**, using wired or wireless debugging. Application ID remains `com.offgrid.messenger`; versionCode increased from 209 to 210. **Do not uninstall** your current app, so the Android Keystore pairing secret remains.
3. Keep both ESP32s on your working 2.0.9 firmware. Connect Android to its existing BLE node as usual.
4. Tap the image icon to choose a photo (48 KiB max after compression); send to the connected web node and verify display + read receipt.
5. Tap the microphone icon to start recording, then stop to send; allow the runtime microphone permission. On the web node, play the note and verify it works. Also test sending a web voice note to Android; Android playback should be user-triggered.
6. Test one photo and one short (3-5 second) voice note first. Avoid starting many large transfers until this version has been tested on your phone.

## Current limitations
- This first media milestone uses the existing **one-radio-fragment-at-a-time Android send queue**. Large files take longer than short text; progress will show. The web uses faster batched photo transmission but Android can already receive it. This is a reliability-first implementation, not full high-throughput media streaming.
- Chat and media history are **in-memory only**, consistent with the Phase 2.0.9 Android foundation: restart may clear them. Secure encrypted on-device history is a later milestone.
- Uses gallery photo picker, not direct camera capture. Does not yet add background notifications, live audio calls, or push-to-talk.
- Android Opus/MP4 playback depends on device/media decoder capabilities; it reports an error rather than pretending playback succeeded when unsupported.

## Verification
Executed Kotlin JVM tests of media payloads, authenticated OGM4 encryption and OGB5/OGC4 reassembly; browser WebCrypto ↔ Kotlin interop in both directions; Android media helper compilation with Android-signature stubs; 2.0.9 source regression, web, radio, receipt and PSA-HMAC tests.
**A full Android Studio/Gradle build and physical phone media transfer have not run in the authoring environment.** If Android Studio displays a compile error, copy its first relevant line and we will fix it before testing further. No firmware flash is needed for this phase.
