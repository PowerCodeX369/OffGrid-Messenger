# OffGrid Messenger — Phase 1.4

**Encrypted, offline browser messenger for two ESP32-C3 Super Mini boards.**

This is an incremental upgrade of the confirmed-working Phase 1.3 ESP-IDF project. The firmware is preserved unchanged: the **browser** implements authenticated AES-256-GCM encryption, multi-packet transfer, image conversion, and final delivered/read acknowledgments. **Do not mix old Phase 1.3 web tabs with this version. Both tabs must run Phase 1.4.**

## Contents

- `main/main.c` — same ESP-IDF firmware as Phase 1.3, ESP-NOW protocol 2, channel 6
- `web/index.html` — app, new attachment picker, pairing controls
- `web/styles.css` — improved light theme, compact SVG overlapping ticks
- `web/logo.svg` — offline embedded logo
- `web/protocol.js` — OGM4 packet encoding, authenticated encryption, fragmentation
- `web/app.js` — Web Serial, pairing, encrypted messages, photo transfer, local encrypted history
- `tests/test-protocol.cjs` — Node.js crypto, packet, tamper-rejection and persistence-schema tests
- `tests/mock-serial.js` — unused browser test fixture (not loaded in app)

## Step 1 — Keep your working firmware

**If both boards run Phase 1.2/1.3 firmware and previously showed protocol 2, do NOT reflash.** The ESP-NOW firmware protocol is unchanged. If a board still uses Phase 1.0 or a different version, flash `main/main.c` using ESP-IDF in VS Code to target `esp32c3`, on both boards.

The two boards can remain connected to the **same laptop via two USB-C cables**. No router, phone app, GPS, or extra radio modules needed for this phase.

## Step 2 — Launch the upgraded web app

Close the old Phase 1.3 tabs. Open a terminal in the new Phase 1.4 project folder:

```bash
python3 -m http.server 8765 --directory web
```

Open two tabs in Chrome/Chromium:

```text
http://localhost:8765/?v=1.4.0
```

If you still see the old interface: Ctrl+Shift+R in **both tabs**. Connect each tab to its own ESP32 USB port using **Connect ESP32**. Each tab should display its correct Wi-Fi MAC and `Connected`.

### Why localhost?

Web Serial and the Web Crypto API require a secure context; `http://localhost` qualifies. `file://` is not the recommended way to run this application. All CSS, JS, and logo resources are local, so **the internet is not needed**.

## Step 3 — Pair your two browsers with a random AES-256 key

1. In **tab A**, click the **security / encryption** entry in the left connection card.
2. Click **Generate new key**. A random 256-bit key is generated on your laptop and installed for Board A.
3. Click **Copy key** (or select and copy the entire 43-character key manually).
4. In **tab B**, click the corresponding security entry, paste the same key, and click **Install pasted key**.
5. Both should show **AES-256-GCM enabled**; the text input and photo attachment button become active.

**Important:** The key is stored for each *local ESP32 MAC address* in IndexedDB as a non-extractable `CryptoKey`, rather than saved as a plaintext password. The same-origin web app can still use the key for authorized operations. Copy and save the raw pairing key securely if you need it later; the app does not have a “show existing key” function. Changing the key deletes the old local messages for that board. Do not post pairing keys publicly or transfer them over the radio link.

Encryption is end-to-end **between the two browser clients**: plaintext never reaches ESP32 firmware. However, physical security, pairing-key sharing, browser integrity, traffic metadata, and USB access are separate concerns. This design does not provide forward secrecy or a formally audited secure messaging protocol.

## Step 4 — Test 500-character text and acknowledgments

Try a message over 180 UTF-8 bytes (for example, 400–500 characters). The browser encrypts and segments it across multiple 180-byte firmware frames. On the receiver, it waits for **all** segments before displaying the decrypted message.

The status indicators are:

- **Clock** — encrypting or transmitting chunks
- **Single grey check** — encrypted transfer sent over the radio; waiting for authenticated recipient confirmation
- **Overlapping double grey checks** — remote browser reassembled, decrypted and authenticated the **complete message** and sent a receipt
- **Overlapping double blue checks** — remote browser confirmed the chat was in focus
- **Exclamation** — a transfer failed. Click its message bubble to retry; repeated data is de-duplicated by message ID

The sender will **not** get a misleading double grey check merely because individual fragments reached the other ESP32. If the other browser has the wrong pairing key, it cannot decrypt and will not send the final receipt.

## Step 5 — Send a photo

1. Keep both nodes connected and paired.
2. Click the `+` button beside the message box.
3. Choose a JPEG, PNG, or another browser-supported image file.
4. The app decodes the image into a canvas and re-encodes it, which strips embedded source metadata such as EXIF location, camera model, and timestamps.
5. The compressed image is **encrypted as one authenticated payload**, segmented into 160-byte encrypted data fragments inside 180-byte radio frames, and transferred with a progress percentage.
6. The receiver only displays the photo after all fragments arrive and the AES-GCM authentication tag verifies. It then sends an encrypted **delivered** receipt. **Read** is acknowledged only when the remote chat is visible and focused.

### Image quality modes

- **Very high quality** (default): WebP with JPEG fallback, quality around 0.88–0.96, reducing image dimensions only if needed to fit the payload limit. This is **lossy**, so preservation of original pixel data is **not guaranteed**.
- **Lossless PNG** (Settings): no deliberate downscaling, metadata stripped by rendering to canvas, PNG lossless encoding of the canvas pixels. If the result exceeds the transfer limit, it **refuses the image** rather than silently changing the quality.

**Maximum encoded photo: 96 KiB.** This cap is intentional for the ESP32-C3's low-rate, ACK-per-fragment transport. A maximum-sized photo requires **around 615 data frames**, plus receipts, and can take a substantial amount of time, especially with retransmissions or interference. You may see smaller images send much faster. A working link is not the same as a fast media link.

A photo cannot be made dramatically smaller by removing EXIF alone while always preserving identical quality. These two modes let you choose the trade-off rather than hiding it.

## Persistent history

Text messages, image contents, and image media metadata are encrypted before being stored in browser IndexedDB, with AES-GCM authenticated to the local node identity and message ID. The paired key is retained across page refreshes. Names, message IDs, timestamps, delivery flags and device MACs can still be observable in local browser storage; this is not a complete disk-encryption product.

Existing Phase 1.3 plaintext browser history is migrated into encrypted storage **after you configure the key**; the old history key is then deleted from `localStorage`. New chat history uses encrypted IndexedDB records. Keep backups if your conversation is valuable; browser storage can be cleared by the browser or user.

## Troubleshooting

- **Still only 180 bytes?** One firmware packet stays limited to 180 bytes; Phase 1.4 fragments the full message in the *browser*, so both web tabs must load new `app.js` and `protocol.js`.
- **Only single grey tick?** Verify both sides have **the same key**, both USB tabs are connected and the peer app remains running. A delivered receipt occurs only after complete authenticated decryption.
- **Blue ticks absent?** Focus the recipient browser tab. Read receipts won't be generated merely when it receives the message in the background.
- **Photo is slow?** Normal for hundreds of stop-and-wait packets over ESP-NOW. Use smaller images. Retry a failed photo by clicking its failed bubble.
- **Photo refused?** Lossless photos often exceed 96 KiB; select Very high quality mode or use smaller input.
- **Browser disconnected?** Reconnect the correct ESP32, then click a failed message to retry. Half-transferred photos are not shown to recipients.
- **History missing?** Verify the same localhost origin, ESP32 MAC, and encryption key. Browser private mode may discard local storage after exit.
- **Firmware mismatch?** Flash the provided ESP-IDF firmware to both boards (target `esp32c3`).
- **Security note:** The default radio transport uses broadcast routing for discovery; the ciphertext is authenticated and encrypted, but packet size and timing remain visible. Keep the pairing key secret.

## Tests

Optional automated packet/cryptography tests (requires Node.js 20+):

```bash
node tests/test-protocol.cjs
```

These verify AES-GCM round-trips, tamper rejection, wrong-key rejection, reassembly of 500 Unicode characters and 96 KiB image payloads, and encrypted local record sealing. The **complete hardware/browser integration has not been tested against your two physical ESP32 boards** in this environment. After installation, test short text, long Unicode text, photos, both tick transitions, errors/retries and browser refreshes.

## Future phases

Phase 1.5: GPS coordinates sharing. Phase 1.6: compressed voice notes. Phase 1.7: notifications and connection/range refinements. Android Studio app remains the **last** phase.
