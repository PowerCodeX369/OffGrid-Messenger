# OffGrid Android — Phase 2.2 Fast Media

Open this `android/` directory in Android Studio, using Android SDK 36 and JDK 17. It has the same application ID and the same pairing/Keystore data as the working 2.1 release; run as an update, not as a clean install.

## New in 2.2
- Reliable three-fragment encrypted media transfer window, with retries and ACK correlation.
- Grouped OGB5 photo/voice ACKs (APPACKB) toward web, preserving instant legacy text receipts.
- Fast photos toggle (28 KiB target, with fallback; high-quality 48 KiB mode).
- Live media speed display in KiB/s.

## Test
`bash tests/run_phase21_tests.sh && bash tests/run_phase22_tests.sh` from `android/` if `kotlinc`, Python and Node.js are installed.

**No ESP32 reflash.** Tested with Kotlin/JVM and protocol-level checks, not an actual Android SDK build in this environment. The working 2.1 hardware release remains the fallback.
