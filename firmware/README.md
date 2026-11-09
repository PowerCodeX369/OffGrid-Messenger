# OffGrid Messenger — Phase 1

**Hardware:** Two ESP32-C3 Super Mini boards, one Linux laptop, USB cables. No LoRa and no internet required for messaging.

**Included:** ESP-IDF firmware (same program on both boards), local Web Serial chat page.

**Does NOT include yet:** Android app/BLE, encryption, delivery/read receipts, GPS, voice notes, message history. Those come after testing the radio + USB foundation.

## 1. Open in ESP-IDF VS Code

1. Install Espressif IDF extension and ESP-IDF 5.2–5.5 (5.5 preferred).
2. In VS Code, **File → Open Folder** → this `OffGridMessenger_Phase1` directory.
3. Run **ESP-IDF: Set Espressif Device Target** → `esp32c3` (or run `idf.py set-target esp32c3` in an ESP-IDF terminal).
4. Select your board's USB serial port (usually `/dev/ttyACM0` for native USB Serial/JTAG).
5. In **ESP-IDF: SDK Configuration Editor**, verify **Component config → ESP System Settings → Channel for console output → USB Serial/JTAG Controller**. `sdkconfig.defaults` requests this automatically, but verify it for your particular board.
6. Build and flash Board A. Disconnect A, then build/flash Board B with exactly the same firmware. No manual MAC configuration is needed.
7. Connect both boards via two USB ports. The two devices can be a few meters apart and should be powered by USB.

**Super Mini variants:** Most C3 Super Minis expose the native USB Serial/JTAG port. Some boards instead use external UART bridges. If yours does, use UART console instead of USB Serial/JTAG in menuconfig, and choose its `/dev/ttyUSB*` port.

## 2. Test ESP-NOW first (without the web UI)

Start serial monitors on BOTH boards at 115200 baud. Each should print something like:

```
BOOT|OffGrid Messenger Phase 1
READY|AA:BB:CC:DD:EE:FF|CHANNEL=6
HELP|Type MSG Hello from A, PING, or HELP
```

Type `PING` followed by Enter on Board A. Board B should show `PEER|<MAC>|PING`; Board A may show `PEER|<MAC>|PONG`.

Then type `MSG Hello from Node A` followed by Enter on A. Board B should show:

```
RX|AA:BB:CC:DD:EE:FF|Hello from Node A
```

Repeat from Board B to A. The boards communicate directly over ESP-NOW, not via the laptop.

**Note:** Some ESP-IDF serial monitors capture keystrokes for commands. For the direct serial test, a normal serial terminal (e.g. `picocom -b 115200 /dev/ttyACM0`) may be simpler. Close the serial monitor before connecting the Web Serial page to that port.

## 3. Test the browser interface on your one laptop

Use modern **Chrome/Chromium** with **Web Serial** support.

In a regular laptop terminal (not the ESP-IDF terminal), from the project folder:

```bash
python3 -m http.server 8765 --directory web
```

Open `http://localhost:8765` in Chrome/Chromium, click **Connect USB**, choose Board A's serial port, and send a message. Board B should show the incoming text in its serial terminal. Reverse direction by typing `MSG Reply from B` on Board B; the browser should show it in a chat bubble.

*Do not have two processes open the same USB serial port at the same time.* For Chrome Web Serial access under Linux, you may need to close ESP-IDF Monitor first, and ensure your user has serial port permission.

For testing two USB boards, one stays connected to the browser and the other stays in a serial monitor.

## 4. Troubleshooting

- **No READY message:** Confirm target esp32c3, correct USB console configuration and port. Reset the board after connecting.
- **USB port inaccessible:** Check `ls /dev/ttyACM* /dev/ttyUSB*`; on Debian/Ubuntu, serial ports are commonly accessible to `dialout` group. On Fedora, permission configuration may differ. Also close other monitors using the port.
- **ESP-NOW doesn't receive:** Both boards must use this exact firmware and fixed channel 6, and must be powered. Test at 1–3 m without walls.
- **Browser doesn't connect:** Use a supported Chrome/Chromium version, and open the page from `http://localhost:8765`, not an HTTP host on your LAN.
- **Message says TX but doesn't arrive:** `TX` means the radio send was queued; it is not a delivery receipt. Reliability comes in the next protocol iteration.
- **Text length:** 180 UTF-8 **bytes**, not characters. Unicode text may use multiple bytes per character.

## Security warning

Phase 1 uses **unencrypted ESP-NOW broadcasts** to establish connectivity. Anybody with suitable receiving hardware within range might read the test messages. Never use this firmware for sensitive/private content. A secure paired unicast protocol with authentication, encryption, replay defenses, and delivery acknowledgements is planned after basic connection tests.

## Project structure

```
OffGridMessenger_Phase1/
├── CMakeLists.txt
├── sdkconfig.defaults
├── main/
│   ├── CMakeLists.txt
│   └── main.c
├── web/
│   ├── index.html
│   └── app.js
└── README.md
```
