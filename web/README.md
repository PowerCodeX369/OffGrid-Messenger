# OffGrid Messenger — browser client

**Authoritative browser code lives here:** `web/`. This is the v2.4.1 browser source carried forward into the integrated v2.5.6 firmware/Android release, with its release-specific JavaScript, HTML, CSS and SVG assets. This is not a claim that all clients share one version number.

## Start locally

From the *repository root*:

```bash
python3 -m http.server 8000 --bind 127.0.0.1 --directory web
```

Open **http://localhost:8000** in a Chromium-family browser with Web Serial support, then connect your ESP32's USB serial device. Localhost provides a secure context for Web Serial. The browser is not served over Wi-Fi by the ESP32 firmware; it talks to the firmware over **USB Serial/JTAG**. Do not host the UI on the public Internet: it provides sensitive operations on a local USB-attached device.

Read [`docs/DEVELOPMENT.md`](../docs/DEVELOPMENT.md) and [`docs/TESTING.md`](../docs/TESTING.md) before flashing anything. Neither the web app nor the firmware has been verified on connected hardware during this Git layout repair.

## Files

- `index.html` — entry point, asset references and UI markup
- `app.js` — browser UI, Web Serial transport and message management
- `protocol.js` — encrypted messaging codec and message framing
- `adaptive.js` — browser-side transfer policies
- `styles.css` — application styles
- `logo.svg` — browser logo used by `index.html`

## Source of truth

Do not add a separate `firmware/web/` copy again. Older historical tags contain it because the original archives packaged it there, but the current integrated checkout intentionally has only `web/`. Firmware host tests use `../../web/` relative to `firmware/tests/`; integration tests should exercise this shared source. If a future device build needs bundled browser assets, generate them as build artifacts without committing another independently maintained copy.

## Fast, non-hardware checks

```bash
node --check web/app.js
node --check web/protocol.js
node firmware/tests/test-protocol.cjs
node firmware/tests/test-selective-ack.cjs
python3 scripts/repository_check.py
```

Do not confuse static checks with proof of functioning radios, BLE authentication, or end-to-end encryption security.
