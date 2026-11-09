# OffGrid RF Stability 2.5.5 — firmware-only, hardware validation candidate

**Do not replace the Android app.** Keep the approved Android 2.5.4 UI and the existing browser files.

## Why the original rate was unstable

The old firmware downshifted PHY on every missing *application* acknowledgment, even if the other ESP32's normal HELLOs were arriving. It also forced LR250 on an incoming LR recovery probe even when the normal link was healthy. This could create repeated speed -> LR -> speed cycles despite close board spacing. RSSI alone is not a reliable proof of receipt delivery.

## Exactly what changed

- Track **regular** peer contact separately from LR recovery probes. Only normal HELLO/data/receipt frames refresh the RSSI input used for rate upgrades.
- Ignore missing application acknowledgments for rate fallback while normal peer traffic was heard within the last 6 seconds. A missing app receipt remains a retry/failure for that message; it is no longer automatically treated as a radio failure.
- Allow fallback if ordinary peer traffic is stale, with a **12-second** recovery cooldown. Preserve the existing 850-ms data retry timer, limits, and frame IDs.
- Require a 7-second dwell between regular RSSI-driven PHY changes, and a current normal-packet sample before any RSSI-driven change.
- Do not obey unsolicited LR probes by forcing LR250 if the normal peer is still being heard.
- Honor explicit RANGE/LR250/LR500 rate settings even when the peer goes missing.
- Add nonbreaking LINK diagnostics: REGAGE (ms since regular peer traffic), ACKRF_SKIP (app ACK timeouts correctly ignored as RF evidence), PROBE_SKIP (LR probe requests that did not trigger PHY downgrade). The web parser ignores unrecognized LINK fields.

Radio payload layout, AES/NVS, web UI, BLE transport, partition table, SDK settings, browser serial commands, and Android UI were **not changed**.

## Install (ESP32-C3 / ESP-IDF 6.1)

1. Save backups of your *working* current firmware and Android project. Keep the pairing keys and NVS untouched.
2. Open this ZIP's `firmware/` folder in ESP-IDF 6.1. Verify `CONFIG_IDF_TARGET="esp32c3"` in `sdkconfig.defaults`, and in your effective `sdkconfig` if present. Do not use an old `esp32` target build directory.
3. Run `idf.py reconfigure && idf.py build`. Confirm the app fits the existing 1.5 MiB `factory` partition. If an old target persists, run `idf.py set-target esp32c3` before reconfigure/build.
4. Flash **one ESP32 only first** (`idf.py -p /dev/ttyACM0 flash` with the actual port). **No erase-flash, no erase NVS.** Test pairing, web, BLE and messages before updating the second board.
5. Once the first is good, flash the second ESP32 to make both rate policies symmetric. Keep ESP-NOW channel 6 and the pairing keys as they are.

### Compare with previous release

With the boards in the same position, use the web diagnostic console or `idf.py monitor` (one at a time) and observe `LINK|` every 2.5 seconds. `CHANGES` is cumulative since boot. After initial convergence, CHANGES should not rise repeatedly during 2–5 minutes of idle time when REGAGE shows regular peer contact. The mode may stabilize at 6 Mbps rather than 12 Mbps depending on RSSI. On purpose, fallback is still possible for real link loss, and a few changes during boot/recovery are normal.

Send a short text, photo and voice in both directions. Verify real delivered/read/played receipts and BLE performance. Walk the boards far apart, then bring them close again and make sure LR recovery still works. Monitor `REGAGE`, `ACKRF_SKIP`, and `PROBE_SKIP` if a switch occurs.

**Rollback:** reflash your previous working firmware image (without erasing NVS). The Android and web files need no rollback because this release does not modify them.

## Test status and limitations

- Passed: host compiler syntax check of main and BLE bridge, policy tests, simulated 3-minute idle with zero changes, loss/cooldown tests, prior SACK, AES/HMAC, binary BLE, watchdog, and partition checks.
- Did not run: a complete ESP-IDF 6.1 build, actual RF spectrum/packet tests, range tests, or live paired-board tests. Results on hardware must be verified. This is a targeted **candidate**, not a proved cure.
- Run source checks with: `bash firmware/tests/run_stability_tests.sh` from the archive root.

See `PRESERVATION_RF_255.json` for exact SHA-256 audit of all existing files. The web directory is included only as an unchanged historical reference; do not overwrite your active web installation.
