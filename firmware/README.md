# Phase 1.1.1 browser compatibility fix

**Issue fixed:** On one browser tab, a cached Phase 1 `app.js` could load with the new Phase 1.1 HTML. That old script renders session/id/hex as raw chat text and never sends `APPACK`, causing all outgoing messages to fail. The HTML now includes a versioned JavaScript URL, visibly identifies the web build, and blocks sending until the board reports `PROTO=2`. The browser retries `STATUS` until it receives that response.

## Upgrade steps

1. Close **both** previous chat tabs and stop the old `python3 -m http.server` process.
2. Extract this ZIP into a **new** folder; run `python3 -m http.server 8765 --directory web` from its project root.
3. In Chrome/Chromium open `http://localhost:8765/?v=1.1.1` in two separate tabs. Force-refresh **both** using Ctrl+Shift+R.
4. Plug in both boards, click Connect USB in each tab and select different serial ports.
5. Wait until **both** show `Connected • MAC • channel 6 • protocol 2`, and `Your board` displays the actual MAC address. Do **not** send messages while the page says checking firmware or waiting for ESP32.
6. Send 'yes' from the left and 'hello' from the right. Both should show readable text and two gray ticks after the remote browser confirms.
7. If any page still displays `session|id|HEX` instead of the message, check DevTools > Network and verify that `app.js?v=1.1.1-fix-20261107` is loaded, not cached Phase 1 `app.js`.

Both ESP32 boards must be running the **same Phase 1.1 protocol 2 firmware**. If they already are, you can **skip reflashing**: only the browser files need replacement. If one page reports a firmware protocol mismatch, reflash that ESP32 with the `main/` firmware in this ZIP.

This is test-only firmware; messages are **not encrypted**. Sender `SENT` means accepted by the radio API, not delivered to the person. `DELIVERED` confirms receipt by the remote browser. The recipient must have the browser tab connected for a delivery receipt.

---

# OffGrid Messenger — Phase 1.1 (reliable text transport)

**Hardware:** two ESP32-C3 Super Mini boards connected via USB-C to one laptop.  
**Firmware:** ESP-IDF 5.x (VS Code ESP-IDF extension).  
**Web:** Chrome/Chromium with Web Serial, two tabs, one per board.

## Changes from Phase 1

- Unique IDs per message, plus a random session ID on each ESP32 boot.
- Seven transmission attempts separated by about 850 ms (approx. 5–7 seconds to conclude failure).
- Receiver-side duplicate history (24 entries) to prevent retries appearing as new messages.
- **Browser-confirmed delivery:** the receiving web app sends `APPACK` back over USB; the receiving ESP32 sends a unicast receipt back to the sender. The sender only shows double checkmarks when that receipt arrives.
- Short 65 ms gap between radio sends, limited outgoing queue (8 messages), clear queue-full/failure signals.
- Automatic `STATUS` command on connection, so opening a browser *after* ESP32 startup still works.
- Message bodies transported over USB as UTF-8 bytes encoded in HEX, preventing `|` or newline from breaking the serial protocol.

This is **not encrypted** yet. Phase 1.1 is strictly for test messages and is neither private nor authenticated. Read receipts (blue ticks) are NOT part of this phase. One tick is **radio transmission queued**, not proof the peer received it. Two ticks mean the remote browser confirmed receiving the message, subject to the limitations of an unencrypted test protocol.

## IMPORTANT: flash both boards

The Phase 1.1 packet format is intentionally incompatible with Phase 1. Both boards **must** be flashed with this new firmware. Keep the two boards on the same Wi-Fi channel 6.

1. Extract ZIP to a fresh directory.
2. VS Code → Open Folder → `OffGridMessenger_Phase1_1`.
3. Open ESP-IDF extension, configure toolchain and set target `esp32c3`.
4. Build and flash board A. Repeat for board B using the *same* firmware.
5. Disconnect ESP-IDF serial monitors before opening Web Serial (only one client can use a USB serial port at a time).
6. Connect both boards by USB to your one laptop.

CLI alternative if ESP-IDF CLI environment is activated:

```sh
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/ttyACM0 flash
# Flash second board (check actual device path)
idf.py -p /dev/ttyACM1 flash
```

## Start the browser

From the extracted project's root:

```sh
python3 -m http.server 8765 --directory web
```

In Chrome/Chromium, open **two separate tabs** with `http://localhost:8765`.

1. Connect tab A to ESP32 A using **Connect USB**.
2. Connect tab B to ESP32 B using **Connect USB**.
3. Each tab should show its own MAC address and channel 6.
4. Send a message from A. See `◷ Sending` → `◷ Queued` → `✓ Sent` → `✓✓ Delivered`.
5. Verify it appears **once** in tab B, even if the transmitter retransmitted.
6. Try the reverse direction.

### Reliability test

- Send 20 messages one at a time in each direction and check for `✓✓ Delivered`.
- Disconnect tab B, then send a message from tab A. It should ultimately show `! Failed`, not a false double-check mark. Reconnecting B while retries remain may allow delivery.
- Reconnect B and resend a *new* message. It should deliver.
- Keep test messages short (max **180 UTF-8 bytes**, not characters). Arabic and emojis take multiple bytes.

## Serial protocol (for debugging)

From browser to board:

- `STATUS` — prints readiness, MAC and session ID.
- `MSGHEX 48656C6C6F` — sends `Hello` as UTF-8 bytes.
- `MSG Hello` — console debugging shortcut (ASCII or UTF-8 through terminal).
- `APPACK AA:BB:CC:DD:EE:FF 123456 7` — confirms to ESP32 that the browser received a particular message.

From board to browser:

- `READY|MAC|CHANNEL=6|SESSION=NNN|PROTO=2`
- `QUEUED|SESSION|ID|HEX` — accepted into outgoing queue.
- `SENT|SESSION|ID` — ESP-NOW accepted the send call locally.
- `RETRY|SESSION|ID|ATTEMPT` — automatic retransmission.
- `RX|MAC|SESSION|ID|HEX` — received and forwarded to browser; browser auto-acknowledges.
- `DELIVERED|SESSION|ID` — receipt confirmed from recipient browser.
- `FAILED|SESSION|ID|NO_APP_RECEIPT` — retries exhausted.
- `ERROR|...` — malformed input or full queue.

## Limitations and next stages

- Phase 1.1 still uses ESP-NOW broadcast for data to support zero-configuration discovery, while receipts use unicast to the sender learned from received frames. The next stages can add paired unicast data, encryption, and peer authentication.
- If both browser tabs share the same physical board, messages cannot travel between two separate nodes: connect each tab to a different ESP32.
- Browser chat history is temporary. A browser reload clears the view; persistence is planned for Phase 1.3.
- App ACK means the browser script processed the message, **not** that a human opened/read the chat. Read receipts will be added later.
- If the receiving browser is closed, the board cannot generate genuine browser acknowledgments. Packets are not durably stored by ESP32; failed messages must be manually resent.
- Receiver duplicate memory is bounded (24 entries) and reset on boot. This is adequate for sequential Phase 1.1 testing, not yet a production-grade exactly-once system.
- Broadcast traffic is unencrypted and unauthenticated. Do NOT send sensitive content or locations yet.
- No BLE, Android, GPS, voice, or professional chat UI in this milestone, intentionally.
- Not compiled or flashed in this delivery environment: build it with your locally installed ESP-IDF. JavaScript syntax and ZIP contents were checked.
