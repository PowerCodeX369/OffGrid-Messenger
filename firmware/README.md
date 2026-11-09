# OffGrid Messenger — Phase 1.4.5 STABLE (recovery)

**Reason for this release:** Phase 1.4.4 connected over USB but made even short encrypted text messages and acknowledgements unacceptably slow. This package intentionally **rolls the ESP-NOW radio transport back to the Phase 1.4 broadcast protocol** that worked on both ESP32-C3 Super Minis, while retaining the modern web UI/gallery and the NVS pairing key you requested. It is a recovery build, not a new feature phase.

## What changed

- **Restored the Phase 1.4 radio path:** broadcast sends, no peer discovery/beacon traffic, no adaptive unicast/PHY switching, no 300-ms per-packet radio callback wait. The receiver's browser still sends an application-level ACK and the sender still retries lost packets.
- **Reduced the original fixed TX gap** from 65 ms to **8 ms**, with **one browser fragment outstanding at a time**. This is a conservative performance experiment, not a promised photo-transfer speed; the actual hardware must verify it.
- **Return to 180-byte USB-to-radio data frames:** encrypted OGM4 fragments are **160 bytes of ciphertext + 20-byte fragment header**. Phase 1.4.3/1.4.4's 228-byte frames are intentionally rejected by the browser handshake for safety.
- **AES-256-GCM still happens in the BROWSER**, through Web Crypto; the ESP32 does **no message encryption or decryption**. The ESP32 acts as a ciphertext relay.
- **Persistent pairing key remains on each ESP32** in NVS, using the existing `offgrid/pairkey` 32-byte blob. A new browser session reads the key over physical USB into RAM only; it doesn't save a new browser key.
- Approved full-screen web UI, light/dark themes, contact naming, encrypted IndexedDB history, 500-character text, overlapped ticks, and in-app photo viewer remain.
- In the stable release, **radio PHY is fixed to 1 Mbps**. This is intentional and doesn't mean every text should take a second. The measured throughput depends on USB traffic, application ACK round trips, congestion and retries. Dynamic rate control can be reintroduced separately only after a validated latency/packet-loss baseline.
- Photo encoding targets ~32 KB in weak/range mode, ~48 KB normally, and ~60 KB in quality mode, subject to source image and actual compression. These modes may resize/lossily compress the photo.

## Critical: Flash BOTH ESP32s

The firmware and web app must match. Do not mix 1.4.4 firmware with the 1.4.5 web app, or 1.4.5 firmware with older 228-byte browsers.

Use ESP-IDF in VS Code, target `esp32c3`. Build and flash the **same code to both nodes**. **Do not use `erase-flash`**: it clears NVS and your existing saved 256-bit key. A normal `idf.py flash` should preserve NVS as long as your partition layout remains compatible.

Terminal equivalent if your ESP-IDF environment is already activated:

```bash
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/ttyACM0 flash
# Flash the other ESP32-C3 too (use its actual port).
```

Native USB Serial/JTAG console configuration lives in `sdkconfig.defaults`. Check its settings if the serial console is not detected.

## Start the browser app

In the NEW project directory:

```bash
python3 -m http.server 8765 --directory web
```

Then open two tabs:

```text
http://localhost:8765/?v=1.4.5-stable
```

Close old tabs and hard refresh both. Keep the **same localhost origin and port** so the previous encrypted IndexedDB history can be accessed. Each tab should connect to a DIFFERENT board through the port picker.

Expected `STATUS` response:

```text
READY|1C:DB:D4:3B:00:FC|CHANNEL=6|SESSION=<varies>|PROTO=2|CAP=180|STABLE=1|NVSKEY=1
```

The firmware also emits `LINK|...|PHY=1|MODE=STABLE` for diagnostics. A known remote MAC and RSSI are only shown after a valid packet arrives; unlike the experimental release, discovery is **not** required before sending the first text.

## Encryption and key pairing

- Existing Phase 1.4.4 keys are read from **the same ESP32 NVS namespace and key name**, so a **normal flash** should preserve them.
- If either node has no key: generate a new 256-bit key on Node A and **install the same key on Node B** via the pairing screen. The node stores it in NVS.
- Every browser connected to a trusted node over physical USB can request the key. This is convenient but **not** robust physical-device security. NVS is not automatically flash-encrypted, and a compromised node or connected host can expose the key. Do not send secrets until this threat model is addressed.
- Chat history remains **per-browser**, stored encrypted in IndexedDB. It is not automatically synchronized to another browser.

## Required acceptance tests (do them IN ORDER)

1. Both boards: connect, confirm `PROTO=2`, `CAP=180`, `STABLE=1`, and saved key loading. If connection is stuck, use the USB diagnostic panel.
2. Send `HELLO A` once from A to B; verify a received bubble and actual double-grey delivered tick. Use a stopwatch to measure end-to-end latency.
3. Send `HELLO B` once from B to A; verify reverse delivery.
4. Repeat 20 short messages each direction, one at a time. Count failures and delays. Do **not** move on until text/ACKs are dependable.
5. Test a 5–10 KB optimized photo, then a 30–50 KB photo. Record KB/s and elapsed seconds. Photo transport still uses fragment ACKs, so **do not assume 1-Mbps PHY implies 125 KB/s application throughput**.
6. Reload one browser and reconnect its ESP32; verify that the saved node key is imported, no new pairing required, and its local chat history decrypts.
7. Test with boards farther apart only after close-range behavior is stable.

## What was verified here

- JavaScript syntax and browser encryption/fragment unit tests: PASS.
- Cross-check of firmware/client protocol size, handshake, NVS API, and rollback path: PASS (static tests only).
- ZIP integrity: PASS.
- **Not yet verified:** actual ESP-IDF compilation, real ESP-NOW wireless traffic, Bluetooth, or physical latency. This environment does not have your two boards or ESP-IDF. A browser E2E test was attempted, but localhost browser navigation was blocked by the execution environment. This release must be physically tested before it can be called fixed.

Run source tests:

```bash
node --check web/app.js
node tests/test-protocol.cjs
python3 tests/test-stable-contract.py
```

`tests/mock-serial.js` is **test-only** and is never loaded in the actual app.

## Rollback

If text still misbehaves, use your archived Phase 1.4 firmware/web pair as a **known-good text-only baseline**. That old version stores the key in browser storage; it is not a drop-in replacement for the NVS key workflow. Retain the Phase 1.4.4 ZIP and your original browser profile until encrypted-history migration has been checked.
