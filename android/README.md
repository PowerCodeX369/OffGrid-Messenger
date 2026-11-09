# OffGrid Android — native Phase 2.0 foundation

Open this folder directly in **Android Studio**, install Android SDK API 36, use JDK 17 and sync project using Gradle 8.14.4 (configuration included). This ZIP intentionally contains no compiled APK and no Gradle wrapper JAR; use a local Gradle distribution or generate the wrapper with Gradle installed.

`app/src/main/java/com/offgrid/messenger`:
- `MainActivity.kt` — Compose blue UI, companion-device pairing, encrypted text transfer and basic receipts.
- `BleNodeClient.kt` — GATT notifications/writes, challenge/response, app-open reconnection.
- `OgmCodec.kt` — pure Kotlin OGM4/OGC4 AES-256-GCM and fragment parsing.
- `SecureKeyStore.kt` — hardware-backed Android Keystore wrapping where supported.

On the **first** connection, open the working USB web app on the trusted ESP32 and copy the existing 43-character base64url key (do not erase NVS or use a different key). For later launches, Android uses its saved key and companion address.

Test the initial Android message against a second ESP32 using the approved browser. Reconnect may require Android Bluetooth/Nearby Devices permission and Bluetooth powered on. No background service or media transfers are claimed in Stage 2.0.


## Phase 2.0.1 build configuration update

- `app/build.gradle.kts`: replaced removed `android.kotlinOptions.jvmTarget = "17"` with Kotlin 2.4 `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`.
- `gradle/wrapper/gradle-wrapper.properties`: Gradle 8.14.4 instead of deprecated 8.13.
- Compose BOM pinned to `2026.06.01`: this is a compatible Compose 1.11-era BOM for existing `compileSdk=36` and Android Gradle Plugin 8.13.2. The original September BOM includes Compose 1.12 requiring SDK 37 and AGP 9.1.1.
- Android `minSdk=26`, `targetSdk=36`, Kotlin 2.4.20, and existing app / BLE / ESP32 code were not changed.

Open the **android** folder in Android Studio and use **Sync Project with Gradle Files**, then **Build > Rebuild Project**. Use JDK 17, Android SDK 36. Android Studio will download the matching Gradle version if needed. The source ZIP does not contain a Gradle wrapper JAR, matching the original project.
