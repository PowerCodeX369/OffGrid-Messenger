# OffGrid Messenger — Phase 1.4.2

**ESP32-C3 Super Mini × 2 · ESP-IDF 5.2+ · Web Serial / ESP-NOW · AES-256-GCM browser messaging**

This release builds on your **approved Phase 1.4.1 web UI**. It adds genuine *per-peer ESP-NOW radio PHY-rate control* in the ESP-IDF firmware, a compact link telemetry line in the sidebar, and an in-app multi-photo viewer. The previously working text, images, history, pairing, receipts, and 500-character cap are kept as-is.

## Important: flash both boards

**Phase 1.4.2 requires reflashing BOTH boards with the same `main/main.c` firmware.**
The browser's encrypted OGM4 packet format and the firmware's `PROTO=2` transport format are unchanged, so your locally stored messages and non-extractable encryption keys are preserved when using the **same browser origin**, e.g. `http://localhost:8765` on the same browser profile.

Requirements:

- Two ESP32-C3 Super Mini boards, two USB-C cables, one laptop.
- VS Code with Espressif's **ESP-IDF extension** and **ESP-IDF 5.2 or later** (5.4/5.5 recommended).
- Chrome/Chromium on a localhost URL (Web Serial).
- No additional radio, GPS, Wi-Fi router, or internet service.

### Build/flash

In VS Code, open this extracted project directory using the ESP-IDF extension. Choose target `esp32c3` and your board's serial port, then **Build → Flash**. Repeat for the second board. Flashing the same project to both boards is correct; their own MAC addresses differentiate them.

Equivalent from an initialized ESP-IDF terminal:

```bash
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/ttyACM0 flash
```

Your actual port may differ (use `idf.py -p ... flash`). Release the serial monitor before connecting Web Serial; **a USB port cannot be open in both programs at once**.

### Start the web client

From this project's root:

```bash
python3 -m http.server 8765 --directory web
```

Open two separate Chrome/Chromium tabs to:

```text
http://localhost:8765/?v=1.4.2
```

Hard-refresh both. Click **Connect ESP32** in each tab, assigning one USB board to each. The browser should show channel 6, firmware `PROTO=2`, and a new `LINK` line containing peer RSSI and radio PHY Mbps. Configure the same AES-256-GCM pairing key on both sides if not already paired. **Do not run the old web JS with the new firmware or vice versa while testing.**

## Adaptive rate design (actual radio changes)

1. The initial transmission uses broadcast at the ESP-NOW default **1 Mbps** to discover the second board.
2. Each node learns the MAC of the first valid peer packet and creates a unicast peer. A per-peer `esp_now_set_peer_rate_config()` call explicitly configures **1 Mbps**.
3. The sending board measures RSSI from return packets and counts **actual recipient-browser ACKs**.
4. After sustained clean ACKs and strong RSSI, it steps to **6 Mbps**, then **12 Mbps**. These are *TX PHY rates*, not end-to-end data rates.
5. On a missing acknowledgment/retry, it immediately falls back to **1 Mbps** before retrying. It also re-uses the broadcast discovery rate after repeated failures so a lost peer does not strand communication.
6. Broadcast discovery and automatic fallback are conservative and keep compatibility with the established transport packet size and acknowledgment protocol.

| Mode | PHY rate behavior | Image & application flow | Intended use |
|---|---|---|---|
| Auto (default) | Start 1 Mbps; promote to 6 or 12 only after good ACKs and RSSI | Existing 1–3 fragment pacing and adaptive image size | Normal use |
| Range first | Always 1 Mbps | 1 in-flight fragment; ~48 KiB target | Poor signal / longer distance |
| Speed first | Start 1 Mbps; promote sooner when conditions support it; fall back on errors | Up to 3 fragments; ~72 KiB target | Short, clean links |

**Actual thresholds used by the firmware:** Auto promotes 1→6 Mbps after 9 consecutive first-try deliveries and RSSI ≥ -65 dBm; 6→12 after 14 clean deliveries and RSSI ≥ -52 dBm. Speed-first requires 5 / 8 clean deliveries respectively, at the same RSSI thresholds. Transitions have a 4.5-second cooldown. Any first missing ACK downgrades to 1 Mbps. These are deliberately conservative starting parameters, **not experimentally calibrated range values**.

The sender can report `1 Mbps` even while the receiving board reports `6 Mbps`: PHY rates are **per transmitter**, so that is normal during one-way traffic.

No PHY-rate selection guarantees a particular maximum distance. The Super Mini's onboard antenna and interference determine real range. RSSI shown is **the signal strength of the packet your board receives**, not a precise distance estimate.

## Serial compatibility

Preserved commands: `STATUS`, `MSGHEX`, `APPACK`, `READ` and the `READY|...|PROTO=2`, `QUEUED|`, `RX|`, `SENT|`, `DELIVERED|`, `READ|`, `FAILED|` formats.

New serial command:

```
RATE AUTO
RATE RANGE
RATE SPEED
```

New informational logs:

```
PEER|1C:DB:D4:3B:36:10
RATE|6|AUTO
LINK|1C:DB:D4:3B:36:10|RSSI=-48|PHY=6|MODE=AUTO|OK=16|RETRY=0|FAIL=0
```

`RSSI=-127` means unknown. `PHY` is the **configured TX rate**; `OK`, `RETRY`, and `FAIL` are transport-level packet counts since the board booted. Link reports are emitted periodically and in response to `STATUS`.

## New in-app photo gallery

- Click a sent or received image bubble to open an **animated fullscreen overlay**, not another tab.
- Browse multiple local encrypted-history photos via the **left/right arrows**, **keyboard arrow keys**, or **swipe** on a touch screen.
- `Esc` or `✕` closes and returns to the conversation.
- `⤢` toggles enlarged scrolling view; `↓` saves the selected decoded photo.
- The viewer uses images already decrypted locally in the browser, never uploads them anywhere.

## Test order (required hardware validation)

1. **Both ESP32s flashed with Phase 1.4.2 firmware.** Connect two web tabs. The link line starts at `1 Mbps`.
2. Send text A→B and B→A; check double grey and blue ticks.
3. Transfer several short text messages, then an image, in **Auto** mode. Check both receipt behavior and `PHY=6`, potentially `PHY=12` when RSSI is strong enough. If the link isn't strong enough, remaining at 1 Mbps is correct.
4. Increase distance slowly. Verify that new retries move back to `PHY=1` while delivery works when radio coverage allows.
5. Select **Range first** in Settings; verify that the board reports `MODE=RANGE|...` and `PHY=1`. Switch back to Auto.
6. Receive at least three photos. Open any image and try next/previous, keyboard arrows, touch swipe (on a touch device), zoom, and close.
7. Refresh both tabs, reconnect, and ensure encrypted history and photos remain. Test dark and light mode and a narrow browser window.

**Safety and security:** End-to-end AES-256-GCM encryption remains implemented by the browser (the ESP32 only relays opaque encrypted packet bytes). RF frames / peer MACs and transmission metadata are not end-to-end encrypted. Secure sharing of the pairing key is still your responsibility. Do not treat the system as security-audited or disaster-grade. Avoid transmitting private coordinates until the intended security review has been completed.

## Test status

- `node tests/test-protocol.cjs`: protocol/encryption roundtrip tests pass.
- Browser UI smoke tests with Chromium passed at 1920px, 850px, and 390px for photo viewer, next/previous navigation, close, and horizontal-overflow checks.
- JavaScript syntax checks pass and ZIP integrity is checked.
- **ESP-IDF compilation, PHY configuration on real C3 radios, and distance measurements were not possible in this environment.** Please flash both and share any `RATE_ERROR`, `LINK` values, or build errors so we can calibrate actual performance.

### Troubleshooting

- **Web sees no ESP32:** close ESP-IDF Monitor and other serial terminals. Check the chosen USB port and the native USB console config.
- **No `PHY` field:** make sure both boards run Phase 1.4.2 firmware and both browser tabs use Phase 1.4.2 JS.
- **`RATE_ERROR`:** your ESP-IDF/driver may not support the requested rate on that chip; fallback to 1 Mbps is used. Report the exact error line.
- **Photo delivery slowly progresses:** photo transfer consists of many authenticated fragments over a narrow-band channel. On weak RSSI the algorithm intentionally favors dependable, slower delivery.
- **Different keys/empty history:** stay on the same localhost origin (same hostname + port), browser, and profile. Old keys are non-extractable from IndexedDB and are not put on the radio.
