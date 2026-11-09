# OffGrid Messenger — Phase 1.4.3.1 (USB connection hotfix)

**For ESP32-C3 Super Mini, ESP-IDF in VS Code, Chromium Web Serial.** This maintenance release keeps all Phase 1.4.3 photo chunk sizes, callback-paced sending, AES-256-GCM browser message format, and photo gallery unchanged.

## What was fixed

- The original browser silently waited for `CAP=228` without displaying the actual received firmware response. It now explains whether the firmware is incompatible or the USB console has not produced any output.
- The ESP32 announces `READY|...|FROM=BEACON` every 3 seconds until it actually receives a browser `STATUS` command. A proper `STATUS` reply includes `FROM=STATUS` to confirm bidirectional USB serial communication.
- The web app displays a small **Diagnostics** option only while waiting, and the diagnostic popup lists non-message firmware/USB startup lines; it can resend `STATUS` manually.
- Firmware now checks task creation success and explicitly reports `FATAL|TASK_CREATE_FAILED` if either worker fails.
- No additional cloud, internet, hardware or libraries required. Original radio PHY adaptation and message payload size are preserved.

## Installation

1. Extract ZIP into a **new** directory (do not use previously compiled `build/` or `sdkconfig` from an old version).
2. In VS Code ESP-IDF extension choose target `esp32c3` and **build + flash both boards**. The native USB console should be enabled (`CONFIG_ESP_CONSOLE_USB_SERIAL_JTAG=y`).
3. Close old tabs and web server. From this new project folder, run:

   ```bash
   python3 -m http.server 8765 --directory web
   ```

4. Open `http://localhost:8765/?v=1.4.3.1` in both Chromium tabs and press Ctrl+Shift+R, then choose the correct board's USB port.
5. After port selection, expect `Connected` within the handshake sequence; if not, click **Diagnostics** in the connection card. Copy the log before reflashing/resetting further.

## Diagnosis guide

- **Incompatible firmware (CAP=180 or missing):** the selected board is on an old build; flash 1.4.3.1 to both ESP32s (make sure you flashed the correct USB port).
- **No serial data:** likely firmware not booting, wrong port, or native USB console config. Try unplugging/reconnecting and flashing a clean project. You can inspect boot logs with `idf.py -p /dev/ttyACM0 monitor` **only after disconnecting the browser** (port cannot be open in both).
- **READY FROM=BEACON but no FROM=STATUS:** board output works but commands are not reaching/being parsed. Copy diagnostics and check the USB input/ESP-IDF console configuration.
- **Boot crash/fatal log:** inspect full ESP-IDF monitor output and fix that error before testing radio or gallery.

## Test

1. Both tabs connect to the correct MAC and report protocol 2 / CAP 228.
2. Verify a short encrypted text both directions.
3. Verify 5–10KB photo, then 40–60KB photo, noting measured KB/s and delivery receipts.
4. Test gallery and light mode; all of those files are unchanged from 1.4.3.

**Limitations:** This environment cannot compile against your local ESP-IDF or test two physical ESP32-C3 boards. JavaScript can be syntax-tested and USB-handshake simulations can be run, but physical compatibility still requires testing.
