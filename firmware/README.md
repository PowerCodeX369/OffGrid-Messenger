# OffGrid Messenger — Phase 1.4.4

**Recovery release: independent ESP-NOW discovery + per-node persistent pairing key.** The approved full-width web design, gallery, image encoding, AES-256-GCM protocol, photo chunk size (208 bytes per app packet), and adaptive 1/6/12 Mbps unicast rate selection remain unchanged.

## Why this release exists

Phase 1.4.3.1 can show **USB Connected** while neither radio has learned a peer. The `LINK|NONE|RSSI=-127...` report is a radio-link problem, not a USB-connection success. The previous firmware learned a peer only after receiving an actual chat frame. In 1.4.4, both nodes independently transmit a small discovery beacon approximately every 1.4 seconds at the default broadcast rate, then learn the other MAC and use unicast to exchange chat frames.

The firmware additionally exports TX/RX and discovery counters so a failed radio link is diagnosable. The browser prevents falsely adding a sent message when it knows that peer discovery has not yet succeeded.

**Caution:** This code is not compiled or tested on physical ESP32-C3 hardware in the development environment. JavaScript/unit/static checks cannot establish that the radio regression is fixed on your boards. Do not consider the release accepted until both directions work physically.

## 1. Flash both boards (mandatory)

Open the folder in VS Code with ESP-IDF 5.2+ installed, choose target `esp32c3`, build and flash **the same firmware** to both Super Minis. Use normal *flash*, not *erase-flash*, because erasing NVS deletes any saved pairing key.

If using a terminal with ESP-IDF installed:

```bash
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/ttyACM0 flash
# Flash the second board on its actual USB port as well.
```

Native USB Serial/JTAG console is configured in `sdkconfig.defaults`. If you have an old `sdkconfig`, verify that `CONFIG_ESP_CONSOLE_USB_SERIAL_JTAG=y` is in effect.

## 2. Run the existing web app

**Keep the localhost hostname and port consistent** so previous encrypted chat history is accessible in the same browser origin.

```bash
python3 -m http.server 8765 --directory web
```

Open two tabs with `http://localhost:8765/?v=1.4.4`; hard-refresh both and connect each to its corresponding USB board.

Expected READY report:

```text
READY|1C:DB:D4:3B:00:FC|CHANNEL=6|SESSION=...|PROTO=2|CAP=228|TXDONE=1|DISC=1|NVSKEY=1|FROM=STATUS
```

## 3. Verify radio discovery *before* chatting

Within a few seconds, the link badge should report the **other** ESP32 as the learned peer with a valid RSSI, not `signal —`. Find diagnostics in **Settings → Radio diagnostics**, then inspect these fields:

```text
LINK|1C:DB:D4:3B:36:10|RSSI=-38|PHY=1|MODE=AUTO|OK=0|RETRY=0|FAIL=0|TXOK=3|TXFAIL=0|RX=2|HELLO=2
```

Numbers are only illustrative. If `LINK|NONE` persists, report TXOK/TXFAIL/RX/HELLO from both boards **before trying images**. If TXOK increases but RX/HELLO stay zero on both, investigate matching channel and radio reception, not AES keys. If TXFAIL grows, inspect the ESP-IDF serial monitor output.

## 4. Configure keys ONCE per ESP32

This version stores a 32-byte key in **each ESP32's NVS**, not in browser IndexedDB or localStorage.

- Connect Node A and choose **Generate and save** on the pairing screen.
- Click **Copy key**, switch to the Node B tab, paste into **Save pasted key**.
- Both nodes now contain the same key. For a **later browser, computer, or fresh browser profile**, connect over USB and the browser will automatically import the node's key into its **RAM only**. You no longer need to pair it again.
- To pair a replacement board, use **Node pairing key → Show node key** on a connected original board and copy it to the replacement.

### Migrating previous Phase 1.4.x chat history

Previously, the browser stored a non-exportable CryptoKey in IndexedDB. It cannot simply copy those key bytes to NVS. Instead, generate a new node key and pair both boards as described above. When you connect in the **original browser profile**, this update tries to decrypt existing records with that prior CryptoKey and re-encrypt them under the node key, then deletes the prior browser key record. Preserve your original browser profile until migration succeeds. A completely new browser won't have access to your earlier encrypted history, even though it can now send and receive new messages through the node.

**Storage security limitation:** NVS is persistent but not automatically protected against flash extraction. This prototype does **not** enable NVS/flash encryption or secure boot. Any device with physical USB access can request the key (`KEYGET`), so a node must be treated as a trusted key-holding device; encryption remains AES-256-GCM on the radio but the system does not provide secrecy from someone who compromises an ESP32 node. See Espressif's NVS encryption and secure boot guidance before protecting sensitive real-world data.

## 5. Radio test, then photo test

1. Both tabs: USB Connected, peer MAC learned, RSSI present.
2. Same 256-bit key installed on both boards.
3. Send `Hello A → B`; verify receiver bubble and double-grey tick.
4. Send `Hello B → A`; verify the reverse direction.
5. Focus receiving chat for blue seen ticks.
6. Only then send a 5–10 KB photo; next try a 40–60 KB photo.
7. Reconnect one node from a **new browser**: verify it loads the stored node key without pairing again.

## Source and testing

- `main/main.c`: ESP-IDF 5.2+ firmware, discovery, NVS storage, diagnostics and existing PHY adaptation.
- `web/app.js`: NVS key handshake, previous-history re-encryption migration, same encrypted messaging UI.
- `web/protocol.js`: unchanged AES-256-GCM, fragment/reassembly and image data envelopes.
- `tests/test-protocol.cjs`: crypto and fragment unit tests using Node.js.
- `tests/test-144-static.py`: firmware/API and web/client contract checks; **not** a substitute for compiling/physical tests.
- `tests/mock-serial.js`: simulated radio bridge for browser developers. This is **not loaded by the actual application**.

Run local tests:

```bash
node --check web/app.js
node tests/test-protocol.cjs
python3 tests/test-144-static.py
```

No Android Studio work was added; that phase remains last.
