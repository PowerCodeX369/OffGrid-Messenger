# OffGrid Messenger — Phase 1.4.11.2: RF Lab / At-Home Long Range Experiments

**Experimental build.** Derived from the user-verified **Phase 1.4.11.1 ManualLR**, using the **Phase 1.4.10.2 InterruptUSB** release as the frozen stable rollback. ESP-IDF 5.2+ / ESP32-C3 / Chromium Web Serial. Two boards and one laptop are sufficient.

## Purpose

Compare Long Range mode on two ESP32-C3 Super Minis sitting close together **without claiming a distant-range measurement**:

1. **Real low-power test:** reduce transmit power on each ESP32 using `esp_wifi_set_max_tx_power()` to weaken received signal. This is a real RF change; displayed RSSI is measured from incoming packets.
2. **Simulated signal:** supply an artificial RSSI to the firmware's **automatic PHY rate-selection algorithm**, allowing it to step into LR 500/250 without changing physical signal strength. Real RSSI remains separately reported.
3. **Real benchmark:** send 32/64/128 short test packets over ESP-NOW and collect ESP32-to-ESP32 ACK success/loss/retries, average ACK milliseconds and effective KiB/s; these do not appear in chat history.

**This cannot prove a 500 m / 1 km range.** Even 20 dBm -> 2 dBm is at most an 18 dB reduction in transmit power, and it does not reproduce multipath, foliage, obstructions, changing antenna geometry or real outdoor noise. The transmitter power range and step mapping are from [Espressif's ESP32-C3 Wi-Fi API](https://docs.espressif.com/projects/esp-idf/en/v5.4.2/esp32c3/api-reference/network/esp_wifi.html).

## What's new (deliberately narrow)

- Firmware UART/USB command grammar: `RFTEST STATUS`, `RFTEST POWER 2|5|10|15|20`, `RFTEST POWER DEFAULT`, `RFTEST SIM -105..-30`, `RFTEST SIM OFF`, `RFTEST PING`, `RFTEST OFF`.
- Transmit-power cap: the firmware snapshots the driver power limit on boot. Test mode can **never set a higher maximum than that original boot limit**; a request above it returns `RFTEST_ERROR|ABOVE_BOOT_POWER`.
- Real power confirmed with `esp_wifi_get_max_tx_power()`; the driver rounds requested values to discrete supported levels.
- Artificial RSSI only feeds `og_lr_target` when the user selects **Auto**; real signal telemetry, peer discovery, retries, received packets and the original message protocol are untouched.
- Every RF experiment is **runtime-only**, never stored in flash/NVS, and automatically resets **120 seconds** after the last test control/heartbeat. While the RF Lab is open, its browser refreshes the lease at 15-second intervals. A reboot or `RFTEST OFF` also restores normal settings.
- UI: Settings → **RF Lab · power & range test**. Includes measured vs. simulated signal, applied power, actual configured PHY, a live ESP-NOW benchmark, and a copyable report.
- No changes to browser-side AES-256-GCM, encrypted IndexedDB history, NVS pairing keys, USB interrupt-driven driver, photo gallery, text ACKs, selective-ACK transfer, watchdog fix, or manual LR quick switch.

## Flash (both boards)

1. Keep your verified `OffGridMessenger_Phase1_4_10_2_InterruptUSB.zip` safe.
2. Open this folder in VS Code with Espressif IDF extension. Set the target to `esp32c3`.
3. Build and flash the same **experimental** firmware to both ESP32-C3 boards, one at a time. **Do not use Erase Flash**: normal Flash preserves NVS pairing keys and remembered peer MAC.
4. Close the serial monitor, then in the project root run:

```bash
python3 -m http.server 8765 --directory web
```

5. Open `http://localhost:8765/?v=1.4.11.2` in two Chromium tabs. Hard-refresh. Connect each tab to its respective ESP32 via Web Serial.
6. First verify encrypted short text **both directions** and one delivered/read receipt. **Do not start an RF test until the basic chat passes.**

## Practical at-home test (same laptop)

### A. Real low-power test

- Open **Settings → RF Lab · power & range test** in both tabs.
- Note the measured RSSI, PHY and benchmark on both boards at default boot power, with both radios set to manual LR 500 Kbps.
- On **both ESP32s**, apply `5 dBm` or `2 dBm`; note each board's **actual applied** power and measured RSSI once new radio packets arrive.
- Run the 32-packet radio benchmark once from Tab A, then once from Tab B. Compare delivered/lost/retries/ACK time and throughput.
- Repeat at manual **LR 250 Kbps**, with the same power setting. Then return both nodes to Auto and press **Restore normal RF** in **both tabs**.
- If the signal is still strong (likely at 20 cm), that's expected: minimum power is not enough attenuation to emulate a long-distance outdoor path. **Never short the antenna against metal or wrap an exposed powered board in conductive foil.**

### B. Simulate weak RSSI to exercise automatic PHY selection

- Set radio mode **Auto** in both tabs (either Settings → Transfer profile or Force LR toggle OFF).
- In RF Lab move the synthetic RSSI slider to `-70`, `-85`, `-95`, or `-105 dBm`, and press **Apply simulated RSSI**.
- Watch **actual configured PHY** after the automatic rate controller's dwell time; rates move step by step with hysteresis and may be held down temporarily after missed ACKs.
- The RF Lab must show **Measured RSSI** separately; synthetic RSSI is **not** a measurement. This test checks the algorithm, not the physical range.
- Press **Use real RSSI** or **Restore normal RF** to return to normal adaptation. Repeat on the other board as necessary.

### C. Report results

- Press **Copy results** in the RF Lab. Include chosen PHY, real RSSI, applied TX power, successful packets, lost packets, retries, ACK ms, and KiB/s.
- Compare under the **same** transmit-power settings. Do not compare radio PHY numbers alone; those are raw rates, not throughput.

## Technical details

Firmware wire messages (only local Web Serial commands; no extra RF message types):

```text
RFTEST POWER 5
RFTEST SIM -95
RFTEST STATUS
RFTEST PING
RFTEST SIM OFF
RFTEST POWER DEFAULT
RFTEST OFF
```

Response examples (**illustrative**, not hardware measurements):

```text
RFTEST|ACTIVE=1|PWR=20|BASE=80|SIM=ON|LIVE=-34|PHY=0.5|MODE=AUTO|PEER=1|TTL=119
RFTEST_SIM|RSSI=-95
RFTEST_EXPIRED|RESTORED
```

`PWR` and `BASE` are in quarter-dBm units; e.g. 20 → 5 dBm, 80 → 20 dBm. `LIVE` is real RSSI. `PHY=0.5` is LR 500 Kbps. `TTL` is seconds before fail-safe reset. The reported `RFTEST|...` line is sourced from firmware state and `esp_wifi_get_max_tx_power()`, not a browser guess.

## Tests included

```bash
node --check web/app.js
node tests/test-protocol.cjs
node tests/test-selective-ack.cjs
node tests/test-adaptive.cjs
python3 tests/test_rf_lab_source.py
python3 tests/test-rf-lab-browser.py
cc -std=c11 -Wall -Wextra -Werror tests/test-rf-lab.c -o /tmp/rflabtest && /tmp/rflabtest
```

These test protocol/parser assumptions, cryptography and browser UI but **are not a full ESP-IDF compilation or physical RF test**. A host-stub syntax check is included in `VALIDATION.md` and cannot replace your ESP-IDF build.

## Troubleshooting / rollback

- `RFTEST_ERROR|ABOVE_BOOT_POWER`: power request exceeds the original Wi-Fi driver/regulatory maximum; select a lower test power.
- `RFTEST_ERROR|POWER_BASELINE_UNAVAILABLE`: driver did not provide an initial power; reset board and check Wi-Fi initialization logs.
- Synthetic value doesn't change rate: select **Auto**, wait for rate dwell, make sure the peer has been discovered and there are no pending messages or rate cooldowns.
- Real RSSI doesn't change much at 20 cm: minimum power cannot necessarily force a weak link at such short range.
- Lost connection after reducing power: press **Restore normal RF**, wait for its 120-second fail-safe, or reboot the node.
- Test build fails or is unstable: flash back the saved **1.4.10.2 InterruptUSB** firmware on both boards. This branch makes no irreversible NVS modifications.

**No GPS/location feature is included or planned.** Voice notes and Android support remain subsequent roadmap items.
