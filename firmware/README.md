# OffGrid Messenger — Phase 1.4.7 Experimental Adaptive PHY

**Stable rollback baseline: OffGridMessenger_Phase1_4_5_2_WatchdogFix.zip**.
This is a separately versioned experiment. Do **not** overwrite or erase your stable baseline.

## What changed

ESP32 firmware now automatically chooses actual ESP-NOW **unicast TX PHY** rates of 1, 6 or 12 Mbps. Unlike Phase 1.4.6 (which changed photo in-flight concurrency only), these settings are applied by `esp_now_set_peer_rate_config()` on the ESP32.

1. Each node broadcasts an OGM2 `TYPE_HELLO` beacon at the default 1 Mbps about every 1.8 seconds. The other node learns its MAC and tracks smoothed incoming RSSI. These are short discovery frames and are not forwarded into chat.
2. After at least three valid RSSI samples and a stable dwell interval (~4.2 s), **Auto** moves from 1 to 6 Mbps at RSSI >= ~-72 dBm; after another dwell it can move from 6 to 12 Mbps at RSSI >= ~-55 dBm. Rates are TX PHY settings, not useful throughput or physical distance measurements.
3. Hysteresis prevents flapping (12 -> 6 below -65 dBm; 6 -> 1 below -81 dBm); signal loss also resets to 1 Mbps. **Range** locks to 1 Mbps. **Speed** uses slightly less conservative thresholds, but still falls back after missing acknowledgments.
4. Peer-to-peer encrypted text/image **data fragments use unicast** at the configured rate, with unchanged OGM2/OGM4 wire format. If no peer is discovered yet, the first frame uses the proven 1 Mbps broadcast. After two unanswered attempts, data reverts to 1 Mbps broadcast to recover.
5. Browser delivery and read ACK control frames remain on 1 Mbps broadcast, so an asymmetric fast link cannot indefinitely prevent the sender from receiving confirmation. If an application ACK is missed, the sender downgrades its unicast rate to 1 Mbps for at least 10 seconds.

Encryption/decryption is **still performed in the browser (Web Crypto AES-256-GCM)**. The ESP32 stores a 32-byte shared key in NVS exactly as before. This is not tamper-proof secure storage and the ESP32 sends the key to a connected browser over USB; protect physical/USB access. MAC-based discovery is not authenticated at the radio layer, although application data has authenticated encryption. These limitations predate this change.

**Not changed**: photo size limits or packet sizes, browser UI layout, persistent chats, crypto format, NVS keys, USB `READY` response fields, reliable message ID/ACK protocol, watchdog fix `pdMS_TO_TICKS(5) + 1` plus `vTaskDelay(1)`, and browser photo throughput Auto/Stable option.

## Requirements

- Two ESP32-C3 Super Mini devices, **both flashed with this new firmware**.
- VS Code ESP-IDF **5.2 or newer**, target `esp32c3`. An ESP-IDF 5.5.x installation is a suitable target.
- One laptop, USB cables, Chromium browser, Python 3 to serve the static site.

## Build and run

1. Extract the ZIP. Open the project directory in VS Code ESP-IDF extension, set target `esp32c3`, build and flash **both** boards. Use normal Flash, **not Erase Flash**, to retain NVS pairing keys.
2. Close old tabs and stop the old Python HTTP server.
3. From the project folder run:

   ```bash
   python3 -m http.server 8765 --directory web
   ```

4. Open two tabs of `http://localhost:8765/?v=1.4.7`, hard refresh (Ctrl+Shift+R), and connect one board to each. Do not run another application's serial monitor simultaneously.
5. Set **Auto** in the existing link preference; keep default photo transfer profile. `READY` includes `ADAPTIVEPHY=1` and the compatible `PROTO=2|CAP=180|STABLE=1|NVSKEY=1` fields. The link badge should show `1 Mbps`, then `6 Mbps` and `12 Mbps` when the boards remain close with a good RSSI for a few seconds.

## Test / recovery sequence

1. Allow **10–15 s** for low-rate discovery then look for `LINK|<peer-MAC>|RSSI=-xx|PHY=12|MODE=AUTO|...` with close nodes. Actual rate changes also appear as `RATE|6|AUTO`, `RATE|12|AUTO`.
2. Send ten short encrypted texts in both directions. Confirm real delivered/seen ticks and no watchdog output.
3. Send one 5–10 KB photo, then a 30–50 KB photo. Track the real KB/s measurement; **12 Mbps PHY does not mean 12 Mbps useful file throughput** because USB and per-fragment application ACK latency still dominate.
4. Increase distance gradually or add obstacles. RSSI drops below the thresholds: 12 -> 6 -> 1 Mbps. Missing ACKs force 1 Mbps and delay re-upgrade. In **Settings**, switch to Range if you want a manual 1 Mbps fallback.
5. Confirm browser reconnection and stored pairing keys still work.
6. If there is **any** text/ACK/USB reliability regression, restore the **frozen 1.4.5.2 ZIP**, flash its firmware onto both boards without erasing NVS, and use its paired web app. The radio design is experimental until proven with your two devices.

### Diagnostics

The serial debug log shows `PEER|<mac>`, `LINK|<mac>|RSSI=-24|PHY=12|MODE=AUTO|...`, and `RATE_ERROR|<rate>|<reason>` if Espressif rejects a rate. If RSSI never appears and `HELLO=0`, peer discovery has not succeeded; do not interpret `Connected` (USB only) as a confirmed ESP-NOW link.

### Tests in the ZIP

```bash
cc -std=c11 -Wall -Wextra -Werror tests/test_phy_policy.c -o /tmp/offgrid-phy-test && /tmp/offgrid-phy-test
node --check web/app.js
node tests/test-protocol.cjs
node tests/test-adaptive.cjs
python3 tests/test-adaptive-phy-contract.py
python3 tests/test-watchdog-fix.py
```

**Validation limitation:** Unit/contract tests are not an ESP-IDF compiler or hardware radio test. This package must be built under your own installed ESP-IDF and tested on the real devices. The original 1.4.5.2 stable files remain unchanged in their own folder/ZIP.
