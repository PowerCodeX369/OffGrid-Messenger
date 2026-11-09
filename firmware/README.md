# OffGrid Messenger — Phase 1.2

Professional, responsive offline web chat. Firmware runs on **two ESP32-C3 Super Mini** boards using **ESP-IDF**. Desktop browsers connect to each board using **USB Web Serial**; the boards communicate using **ESP-NOW on channel 6**. No router, internet access, or extra radio is required.

## Important safety and feature boundaries

**Phase 1.2 is an UNENCRYPTED TEST BUILD.** Do not transmit private or sensitive information. AES-256-GCM end-to-end encryption, persistent chat history, file transfer, voice notes, GPS and the Android BLE app belong to later phases. This web-only phase requires both browser clients to remain open for end-to-end delivery receipts and read receipts. Read receipts are *best effort* and not persisted if a client disconnects.

## What is new in 1.2

- Modern messaging UI: sidebar, responsive chat, mobile-width navigation, message bubbles, timestamps, contact naming, dark/light mode, connection status, 180-byte UTF-8 length meter, failure states.
- **Single tick** = sender ESP32 submitted message for ESP-NOW transmission.
- **Double grey tick** = recipient *browser* displayed the text and sent `APPACK`; originating ESP32 received its delivery ACK.
- **Double blue tick** = recipient browser had chat visible and focused, then sent `READ` back across ESP-NOW, and sender browser received it.
- Retries, duplicate protection and sender IDs preserved from Phase 1.1.
- Both boards use the SAME firmware; each has its own hardware MAC and random boot session.

**A focused browser is required for blue ticks.** If you have two tabs in the same Chromium window, only the active tab is normally focused. To test blue ticks, select the receiving tab *after* sending a message. To view both clients simultaneously, open two separate browser windows side by side and click the receiving one to mark as read.

## Build / flash

1. Open this project directory in **VS Code** with the **Espressif IDF** extension (ESP-IDF 5.x recommended).
2. Select target **esp32c3**.
3. Build and flash **Board A**.
4. Unplug, attach **Board B**, then flash exactly the same firmware.
5. Power both via two USB-C cables connected to the laptop.
6. Expect `READY|<MAC>|CHANNEL=6|SESSION=<number>|PROTO=2` on each board. The browser queries `STATUS` if it misses this initial line.

CLI equivalents (with the ESP-IDF environment initialized):

```bash
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/ttyACM0 flash
```

When flashing the second board, select its actual port as appropriate. On Linux, permissions may require membership in `dialout` (or your distribution's equivalent group). Close any serial monitor before connecting a browser tab.

**Both boards MUST run this 1.2 firmware for genuine blue read receipts.** Older 1.1.1 boards can still process normal messages, but they do not understand the new read-receipt frame.

## Run web app

With both ESP32 nodes connected via USB, launch from **this project folder**:

```bash
python3 -m http.server 8765 --directory web
```

Open `http://localhost:8765/?v=1.2.0` in **Chromium / Chrome** and open a second tab or window at the same address. In each, click **Connect ESP32**, then pick a different USB serial device (do not select the same board in both). The app uses Web Serial, which needs a supported browser in a secure context (localhost is supported). No internet requests, remote CSS libraries, or cloud dependencies are present.

## Acceptance checks

1. Both browser clients show **Connected**, unique MAC addresses, channel 6, protocol 2.
2. From A, send `Hello from A` to B. It should display at B, then show **double grey tick** at A.
3. Click/focus B's chat tab or window. A should switch to **double blue tick**.
4. Reverse B → A; verify both receipt states.
5. Send the same text 3 times quickly. Each must appear exactly 3 times, not 1 or 9 times.
6. Disable/disconnect B's browser. Messages from A should ultimately show **Failed** rather than falsely show Delivered.
7. Rename the contact in the chat header (pencil icon). Reload and verify its display name remains.
8. Switch theme; inspect layout at mobile-sized window. Theme/name persist in localStorage, but messages do not.

## Why there are no unread counters yet

There is only one peer in this two-node prototype; conversation persistence and a multi-contact model are deferred to Phase 1.3. Blue ticks are already genuine, but there is no read-receipt queue across disconnected clients; such persistence will be addressed in later stages.

## Protocol (v2)

Browser → ESP32 USB serial:

- `STATUS`
- `MSGHEX <UTF8_HEX>`
- `APPACK <SENDER_MAC> <SESSION> <ID>` — browser received text
- `READ <SENDER_MAC> <SESSION> <ID>` — browser viewed chat

ESP32 → Browser USB serial:

- `READY|<MAC>|CHANNEL=6|SESSION=<BOOT_SESSION>|PROTO=2`
- `QUEUED|<SESSION>|<ID>|<HEX>`
- `SENT|<SESSION>|<ID>`
- `RX|<SENDER_MAC>|<SESSION>|<ID>|<HEX>`
- `DELIVERED|<SESSION>|<ID>`
- `READ|<SESSION>|<ID>`
- `FAILED|<SESSION>|<ID>|<REASON>`
- `ERROR|<CODE>`

Radio frames: protocol 2 magic `OGM2`, types `TEXT=1`, `DELIVERY_RECEIPT=2`, `READ=3`. This is unauthenticated and unencrypted during testing. A malicious nearby actor could forge frames; **do not use for sensitive information**.

## Troubleshooting

- Browser says unsupported: use a current Chrome/Chromium, not Firefox; open `localhost` rather than `file://`.
- USB busy: disconnect ESP-IDF Serial Monitor, Arduino Serial Monitor, or other application holding the port.
- `READY` missing: ensure both boards were flashed with this project; disconnect/reconnect.
- Grey ticks but no blue ticks: focus the **recipient** tab/window; blue requires the recipient to have an open, visible chat, then the sender's client must still be connected.
- Delivered fails: close stale tabs, hard-refresh both pages, and make sure both nodes are powered and flashed.
- Message disappears on refresh: by design for Phase 1.2; persistent history comes in Phase 1.3.
- Duplicate chat messages after retries: should be suppressed with sender MAC + session + ID. If duplicates appear, capture the browser console and both devices' boot output.
