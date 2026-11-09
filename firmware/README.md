# OffGrid Messenger — Phase 1.4.11 (Experimental ESP-NOW Long Range)

**Hardware:** two ESP32-C3 Super Minis, one laptop, USB-C cables. **Firmware:** ESP-IDF 5.2+ (ESP32-C3 target) in VS Code. **Web app:** local Chromium Web Serial. **No LoRa radio, Wi-Fi AP, router, cloud server, or GPS required.**

> **IMPORTANT:** This is an **experimental branch**, not a proven outdoor-range release. I verified the source against Espressif documentation, compiled C syntax using host stubs, and ran local policy, cryptographic, and packet tests. The actual firmware has **not** been built using the ESP-IDF toolchain or tested on physical ESP32s in this environment. The user-verified **1.4.10.2 InterruptUSB baseline is frozen and not replaced**.

## What this phase adds

- Enables both ordinary 802.11 b/g/n and Espressif proprietary **Wi-Fi Long Range (LR)** on ESP32-C3.
- Supports real peer transmission rates **12, 6, 1 Mbps; LR 500 kbps; LR 250 kbps** using `esp_now_set_peer_rate_config()` after registering the peer.
- Settings -> Transfer profile now has **Auto, Standard 1 Mbps, Speed first, Manual LR 500 kbps, Manual LR 250 kbps**.
- Automatic selection considers RSSI and retry/ACK failures. It cannot directly measure distance.
- LR **unicast** transports application receipts, selective photo receipts, read receipts, and radio benchmark ACKs whenever the peer MAC is known. Normal-rate broadcast ACKs cannot sustain communication beyond ordinary range.
- ESP32 retains the first discovered peer's MAC address in its existing `offgrid` NVS namespace under the new key `peer_mac` (not an encryption key). It can send 250 kbps LR recovery probes after a restart or interruption, even if ordinary broadcast discovery no longer reaches the other node.
- Maintains normal 1 Mbps broadcast HELLO for nearby first discovery and backwards-compatible ordinary rates.
- Reuses the **same IRQ-driven USB Serial/JTAG receive path**, firmware protocol `PROTO=2`, browser-side **AES-256-GCM**, NVS pairing keys, photo/gallery, selective ACKs, and auto-scroll.

## Hardware setup and first pairing

1. **Back up** your original Phase 1.4.10.2 baseline ZIP and keep it untouched.
2. Place **both ESP32-C3 boards about 1–3 meters apart** initially. Very close proximity can sometimes distort RSSI readings.
3. In VS Code open this project, select target `esp32c3`, build and flash **both** ESP32 boards. Use normal Flash; **do not erase flash / NVS**. Select ESP-IDF 5.2+ (prefer a recent stable 5.x version).
4. Close ESP-IDF monitor processes, then from this extracted project's root run:

   ```bash
   python3 -m http.server 8765 --directory web
   ```

5. Open `http://localhost:8765/?v=1.4.11` in two Chromium tabs (each tab connects to a different ESP32). Hard refresh with Ctrl+Shift+R.
6. Confirm both connections show `PROTO=2` and the diagnostic serial console includes `LRMODE|ENABLED|BGNLR`. If LR initialization fails, regular text remains available and the web app warns that LR was not enabled.
7. Let the two ESP32s **discover each other at normal range** before walking farther away. Once a node learns a peer, it saves the MAC in NVS. This is needed for LR recovery after restarting outside the reach of 1 Mbps broadcast.
8. Test ordinary encrypted text **both directions**. Confirm single, delivered, and read ticks. If the key isn't already installed, pair the two boards with the same key before messaging.

## Manual LR radio tests (do these before Auto outdoors)

On **both browser tabs**, open **Settings -> Transfer profile**:

- Choose **Manual LR 500 kbps** on both; verify the live telemetry shows `PHY=0.5` (Mbps), send 5 short texts both ways.
- Choose **Manual LR 250 kbps** on both; verify `PHY=0.25`, send 5 short texts both ways and check delivery/read receipts.
- Select **Auto** on both. At strong signal it may transition back through 1, 6 and 12 Mbps after a dwell period. The firmware holds lower speeds temporarily after failures.
- After nearby manual LR tests pass, gradually increase separation outdoors. Record RSSI, selected PHY rate, delivery success, latency, and any recovered failures at each distance.

**Do not assume a particular maximum range:** Espressif's LR mode is designed for greater reception sensitivity and offers a roughly 1 km line-of-sight *capability* under suitable conditions, not a guarantee for the small PCB antennas of a Super Mini.

## How connection recovery works

- The boards always broadcast ordinary 1 Mbps `HELLO` packets for first discovery nearby.
- On the first valid peer, the MAC is stored in NVS. For a later startup, saved peer information lets the radio add the same peer *before* hearing ordinary discovery.
- On missed application ACKs, transmit rate backs down progressively: **12 -> 6 -> 1 Mbps -> LR 500 -> LR 250 kbps**. The retry is **unicast to the saved/learned peer**, not converted to short-range broadcast.
- If the link becomes stale, the node also sends a short `HELLO` **unicast at LR 250 kbps**, and the receiving node answers with LR unicast. This recovery does not require the browser to run a discovery protocol.
- Periodic normal HELLO remains active for ordinary nearby rediscovery. Automatic upgrades require RSSI samples, a dwell interval and an empty pending queue.

**Known limitation:** First-time discovery beyond 1 Mbps range is not guaranteed without the nodes learning each other nearby first. Peer discovery is not cryptographically authenticated at the radio layer; end-to-end application payload encryption still requires the valid shared AES-256-GCM key. Reprovisioning a different peer may require clearing the saved `peer_mac` NVS entry or flashing a specially prepared provisioning tool; do not erase all NVS unless you're prepared to re-pair keys.

## Important USB / radio diagnostics

Examples (values are illustrations, **not observed measurements**):

```text
LRMODE|ENABLED|BGNLR
READY|...|PROTO=2|CAP=180|...|LR=1|FROM=STATUS
LINK|...|RSSI=-86|PHY=0.5|MODE=AUTO|...|LR=1|LRPROBE=3|LRRX=2|LRFALLBACK=1|PHYMODE=LR500
RATE|0.25|LR250
```

- `PHY=0.25` means **250 kbps PHY**, not file throughput. The photo-transfer rate may be far smaller because of application ACKs, encryption envelopes, and USB/network overhead.
- `LR=1` shows the Wi-Fi driver enabled LR capability; `RATE_ERROR|...` means an actual per-peer radio configuration failed.
- `LRPROBE` / `LRRX` are counts of LR recovery probes sent / received, useful during outdoor testing.
- If either board gets stuck on `READY`, inspect the serial diagnostics and **do not assume it is an LR range issue**. The USB IRQ path and watchdog safeguards remain unchanged.

## Performance acceptance tests

1. Reconnect the browser 3 times: no stuck `READY` handshake or watchdog warning.
2. Near range Auto: 20 encrypted text messages in each direction, checking delivery and read receipts.
3. Manual LR 500 and manual LR 250: 5 texts each direction, each with correct delivery receipts.
4. Test photo transfer in **Stable/Legacy ACK** mode first, then optional Selective ACK. Slow photo throughput in LR is expected.
5. Disconnect and restart the nodes **after first nearby peer discovery**, then confirm LR recovery probes and messages at a distance that is still within tested range.
6. Run existing Speed diagnostics and record USB upload/download, ESP-NOW-only, encrypted full-path and actual photo speed, plus PHY and RSSI.

## Local tests

Run from the project directory (requires C compiler, Node.js and Python):

```bash
cc -std=c11 -Wall -Wextra -Werror tests/test-lr-policy.c -o /tmp/offgrid-lr-test
/tmp/offgrid-lr-test
python3 tests/test-long-range-contract.py
node tests/test-protocol.cjs
node tests/test-selective-ack.cjs
node --check ../web/app.js
```

**Note:** Host-stub C syntax tests are not equivalent to compiling against ESP-IDF. Build the project in VS Code before flashing. If the full build fails, share the build error rather than flashing partial firmware.

## Rollback

The stable baseline is `/OffGridMessenger_Phase1_4_10_2_InterruptUSB.zip` (distributed separately). Keep that ZIP safe; flash its firmware on both boards to restore the previous known-good radio behavior. Its older firmware ignores the extra `peer_mac` NVS key. Your original AES pairing keys remain preserved when you use normal Flash rather than Erase Flash.

## Documentation used

- [Espressif ESP-NOW, ESP32-C3](https://docs.espressif.com/projects/esp-idf/en/latest/esp32c3/api-reference/network/esp_now.html)
- [Espressif Wi-Fi LR overview, ESP32-C3](https://docs.espressif.com/projects/esp-idf/en/latest/esp32c3/api-guides/wifi-driver/overview.html)
- [Espressif official ESP-NOW example](https://github.com/espressif/esp-idf/tree/master/examples/wifi/espnow)
- [ESP-IDF 5.4.2 Wi-Fi PHY rate definitions](https://docs.espressif.com/projects/esp-idf/en/v5.4.2/esp32c3/api-reference/network/esp_wifi.html)

## Next roadmap

After LR is validated on the two actual boards: short encrypted voice notes, offline notifications and reconnect polish, followed by Android Studio / Kotlin app **last**. GPS/location sharing remains removed from scope.
