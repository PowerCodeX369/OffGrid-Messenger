# OffGrid Messenger Phase 2.3 — Authenticated Binary BLE Upload

**Hardware build NOT tested here.** Release candidate based on Phase 2.2, which user confirmed works on real hardware.

## Why this change exists
User measured ~1.0 KiB/s Android -> web, ~4.5 KiB/s web -> Android (Phase 2.2).
Phase 2.2 uses `MSGHEX` ASCII text over BLE and emits full hexadecimal `QUEUED` echoes.
For a 180-byte frame, that means up to 368 ASCII bytes (often three GATT writes at negotiated MTU 247).

Phase 2.3 transports the exact same already-encrypted 180-byte OGC radio frame as 185 bytes:
`B7 47 01 <token_lo> <token_hi> <payload_bytes 1..180>`.
Binary sends are enabled only for multi-fragment photo/voice transfer; short text and delivery/read/played receipts intentionally keep the hardware-tested MSGHEX path.
The unchanged NimBLE write characteristic is used: same UUID, same WRITE_TYPE_DEFAULT, same connection authentication.
Authenticated command `BINCAP` receives `BINCAP|1|180`. Clients only use binary when reported and ATT MTU >= 188.
A binary write generates `BQUEUED|<token>|<radio_session>|<radio_packet_id>` instead of copying the full hex frame back over BLE.
`DELIVERED`, `FAILED`, encrypted end-to-end chat receipts, and `RX` notifications are unchanged.

## Installation — test ONE board first
1. **Back up Phase 2.2 Android and Phase 2.0.9 ESP32 firmware that are working.** Do not erase NVS.
2. Open `firmware/` using **ESP-IDF v6.1**. Confirm `idf.py set-target esp32c3` (only if needed), `idf.py reconfigure`, `idf.py build`. Check binary and partition fit before flashing.
3. Flash **only the phone-side ESP32 A** with `idf.py -p /dev/ttyACM0 flash` (adjust port). Do NOT run `erase-flash`, `flash_both`, or factory-reset commands. Its NVS pairing key stays intact.
4. Keep ESP32 B at the working firmware and continue using the existing 1.6.1 web interface. The ESP-NOW radio packet format did NOT change, so cross-version pairing is supported.
5. Open `android/` in Android Studio. Build and run as an in-place upgrade, keeping app data and pairing key.
6. Reconnect BLE and inspect OffGridBLE logs for `Binary BLE capability confirmed; active=true`. A value of `false` indicates MTU/negotiation fallback.
7. Send short encrypted text; confirm ticks/read receipts; send a ~20KiB photo; compare KiB/s. Reverse direction should behave as before.
8. If the node fails to boot or throughput regresses, **flash the existing 2.0.9 board firmware** without erasing NVS and reinstall 2.2 Android.

## Compatibility
* Old Android client -> new firmware: original newline-delimited MSGHEX, STATUS, APPACK(B), READ work as before.
* New Android client -> old firmware: BINCAP rejected, client remains on ASCII MSGHEX and full QUEUED echo.
* New Android -> new firmware with MTU < 188: client remains on ASCII fallback.
* Web -> ESP32 USB: unchanged. ESP-NOW over-the-air structure and encryption untouched.
* Binary BLE frame is allowed only after the same HMAC challenge/response authentication as before.
* Binary request token is an untrusted *correlation ID*, not a message ID or cryptographic nonce. Encryption and authentication remain in OGM4 and BLE's challenge handshake.

## Limitations
* ESP32 NimBLE and Android devices are not available to this container, so a complete IDF build or Android Studio build and hardware throughput benchmark cannot be run here.
* This upgrade removes hex wrapping on **Android-to-node writes**, not web-to-Android RX notifications. It is expected to help primarily Android -> web; 4.5 KiB/s reverse throughput is not guaranteed.
* Real speed remains constrained by Wi-Fi/BLE coexistence, radio ACKs and AP/OS scheduling. `WRITE_TYPE_DEFAULT` remains intentionally reliable (not unverified write-without-response).
