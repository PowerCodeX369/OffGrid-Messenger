# OffGrid Messenger — Phase 1.4.10.2 USB interrupt-driver experiment

**Status: experimental, not hardware verified.** Based on **Phase 1.4.10.1 USBRecovery**, which you measured at **2.45 KiB/s USB upload, 116.91 KiB/s USB download, 15.63 KiB/s ESP-NOW-only and 1.70 KiB/s encrypted end-to-end**. The authoritative stable baseline remains **Phase 1.4.5.2 WatchdogFix**. Do not overwrite either rollback ZIP.

## Scope of this release

This release changes **only the native USB receive mechanism** on the ESP32-C3. It installs Espressif's interrupt-driven USB Serial/JTAG driver with a 4 KiB RX ring buffer and a 4 KiB TX ring buffer, directs normal stdout through that same installed driver and reads input in batches of up to 256 bytes using `usb_serial_jtag_read_bytes()`.

The same character-by-character **command parser** is reused for both driver and legacy modes, including partial-line handling. This fixes the main error in the older 1.4.10 attempt, which called `read()` on the *unmodified, nonblocking VFS console*, without installing an interrupt-driven driver.

**Preserved:** ESP-NOW adaptive 1/6/12 Mbps, USB READY/STATUS handshake, 500-character encrypted browser messages, AES-256-GCM in the browser, key persistence in ESP32 NVS, photo transfer and ACK protocol, read/delivery receipts, radio/USB speed diagnostics, gallery, approved UI and the task watchdog fix.

The web folder has **no changes** from the measured 1.4.10.1 release. This does not add batch photo acknowledgments.

## Build (VS Code + ESP-IDF)

Requirements: ESP32-C3 Super Mini with **native USB Serial/JTAG port** (often `/dev/ttyACM*` on Linux) and **ESP-IDF 5.2 or newer**. In the ESP-IDF extension open the project folder, set target `esp32c3`, build and flash.

```bash
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/ttyACM0 flash
```

Adjust the port to your actual board. Use normal Flash, **not erase-flash**; erasing would remove the NVS pairing key.

After reset, the USB terminal should show one of:

- `USBMODE|IRQ|RXBUF=4096|TXBUF=4096` — experimental driver is running.
- `USBMODE|LEGACY|DRIVER_INIT=...` — initialization failed, legacy stdio input is active.

The ESP32 should also continue to send its existing `READY|...|PROTO=2|CAP=180...` handshake. Close the ESP-IDF serial monitor before connecting in the browser; the port cannot normally be opened by both simultaneously.

## Web interface

From the extracted project root:

```bash
python3 -m http.server 8765 --directory web
```

Open `http://localhost:8765/?v=1.4.9` in Chromium. Because the browser files are unchanged, existing pairing, encrypted history and diagnostics are compatible at the same browser origin and port. If the page looks cached, hard-refresh (Ctrl+Shift+R). Both windows can use the bundled web app.

## Controlled test procedure

1. **Flash only Board A first**. Leave Board B on the currently measured-working 1.4.10.1 firmware. This USB-only change does not change the radio protocol.
2. Confirm the `USBMODE|IRQ` line and normal `READY` handshake on Board A.
3. Connect Board A in the web app. Run **USB PC → ESP32** benchmark; record KiB/s, duration, and any timeout. Compare with **2.45 KiB/s**.
4. Run **USB ESP32 → PC** benchmark; compare with **116.91 KiB/s**.
5. Keeping Board B connected with the same pairing key, confirm a short encrypted text sent in **both directions** and correct double ticks.
6. Run **ESP-NOW-only** and **Encrypted browser-to-browser** benchmarks; compare with **15.63 KiB/s** and **1.70 KiB/s**. Then try a small image and compare with **2.1 KiB/s** actual previous photo transfer speed.
7. If upload, chat or ACKs regress, roll back Board A to the original 1.4.10.1 firmware. Do not change Board B or the web app until the experiment passes.
8. Flash Board B only after the new driver path passes all tests. Run the four diagnostics again on each node.

There is no guaranteed improvement: the driver may improve inbound USB scheduling, but browser writes, line-hex framing and per-fragment acknowledgments can still limit the encrypted end-to-end rate.

## Quick fallback without replacing source files

The project includes an ESP-IDF build setting:

`idf.py menuconfig` → **OffGrid USB input experiment** → uncheck **Use interrupt-driven native USB Serial/JTAG receive (experimental)**.

Rebuild and flash; the firmware uses the exact legacy `getchar()` + `vTaskDelay(1)` input method. The app will report `USBMODE|LEGACY|BUILD_SETTING`. This is a *compile-time* selection, not a runtime feature toggle.

For a complete measured rollback, use **OffGridMessenger_Phase1_4_10_1_USBRecovery.zip**. For the original stable fallback, use **OffGridMessenger_Phase1_4_5_2_WatchdogFix.zip**.

## Verification performed before delivery

- Host C syntax-smoke tests with ESP-IDF header stubs for both driver-on and driver-off modes.
- Byte-for-byte comparison of all web assets and radio-rate policy against 1.4.10.1.
- Verified command parser contents were not changed.
- JavaScript and protocol tests supplied by the project.
- ZIP integrity check.

**Not done here:** a full ESP-IDF firmware compile, hardware USB driver initialization, watchdog test on ESP32-C3 or speed measurement. Please share any ESP-IDF compiler error or new diagnostics so this experimental branch can be assessed before promotion.

### Technical implementation note

Unlike calling `read(STDIN_FILENO, ...)` against the default VFS console (which caused the earlier 1.4.10 regression), this experimental path uses `usb_serial_jtag_driver_install()`, the corresponding VFS driver mode for stdout, and `usb_serial_jtag_read_bytes()` from the driver's interrupt-fed receive buffer. Only the dedicated serial task consumes incoming bytes; both modes use the same line-oriented commands.
