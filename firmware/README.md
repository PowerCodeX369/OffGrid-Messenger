# OffGrid Messenger — 1.4.5.1 USB Handshake Hotfix

## What this fixes

The 1.4.5 release removed the READY beacon loop from earlier connecting releases. A board can boot before Web Serial opens, leaving its one-time READY unseen. If the ESP32 does not see the browser's STATUS command, both screens remain on “Checking firmware” indefinitely. This release re-announces READY every 2.5 seconds, even after browser reconnects, and displays real USB diagnostics. The radio transport, payload size, Web Crypto, and NVS key storage are unchanged.

## Firmware update

Flash **both** ESP32-C3 boards with the same project, ESP-IDF target `esp32c3`.
Use **Flash**, not **Erase Flash**. A full erase deletes your NVS pairing keys. If ESP-IDF reports an NVS partition migration or partition change, back up the key before any erase and re-pair afterward.

Build from VS Code ESP-IDF extension, or an activated ESP-IDF terminal:

```bash
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/ttyACM0 flash
```

If you already selected the target and have existing `sdkconfig`, `set-target` is not necessary. Ensure the console uses the ESP32-C3 USB Serial/JTAG port; `sdkconfig.defaults` is only applied when a new `sdkconfig` is generated.

## Web app

Run from this exact new folder:

```bash
python3 -m http.server 8765 --directory web
```

Open two tabs at `http://localhost:8765/?v=1.4.5.1-stable`. Connect each tab to a different board. Keep the same localhost origin and port for encrypted local history. Hard-refresh if needed.

Expected line (every 2.5 seconds and after STATUS):

```
READY|1C:DB:D4:3B:00:FC|CHANNEL=6|SESSION=123456|PROTO=2|CAP=180|STABLE=1|NVSKEY=1|FROM=BEACON
```

If both boards connect, confirm the node keys load, then test a **short encrypted text both ways**, before sending photos. The ESP32 still does NOT encrypt chat data: the browser does AES-256-GCM; the node stores the key in NVS.

## Diagnostics if it still waits

Click **Diagnostics** beside the USB connection warning. A serial log is now collected, with `KEYDATA` redacted. It differentiates:

- **No incoming serial lines:** firmware may not have booted, USB console may be configured for UART instead of USB Serial/JTAG, or wrong port/cable was selected.
- **Incompatible READY:** old firmware or stale browser bundle. Look for the `PROTO`, `CAP`, `STABLE`, `NVSKEY` markers.
- **READY arrives but node key doesn't load:** USB output works; `KEYGET` command input/firmware processing needs investigation.

A fast check without reflashing: connect the old 1.4.5 browser and press your ESP32 reset button once. If `READY` then appears, a missed boot announcement is strongly implicated.

If even this version does not show `READY`, open the board with an ESP-IDF serial monitor (closing the browser's port first) and copy the boot log. Do not copy any `KEYDATA` line, as that would expose the shared key.

## Validation

Syntax, source-level handshake, and existing protocol unit tests can be run with:

```bash
node --check web/app.js
node tests/test-protocol.cjs
python3 tests/test-stable-contract.py
python3 tests/test-handshake.py
```

This environment cannot compile the ESP-IDF project or measure ESP-NOW latency on the real boards. Treat hardware results as unverified until both boards connect and exchange messages.
