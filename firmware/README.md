# OffGrid Messenger — Phase 1.4.6 Safe Adaptive Photo Transfer

## Frozen baseline

**Phase 1.4.5.2_WatchdogFix is the authoritative stable baseline.** All ESP-IDF files, `main/main.c`, `sdkconfig.defaults`, `web/protocol.js`, and `web/styles.css` are byte-for-byte unchanged. Flashing the boards is **not** required for this update. Keep the old ZIP as a rollback image.

## What changed

Only the browser photo transfer scheduler and its settings/label have changed:

- Photo compression now also respects weak RSSI before encoding (lossless PNG mode is unchanged). Text messages, delivery/read controls, NVS pairing keys, AES-256-GCM, authenticated transfer protocol, photo gallery, and local history unchanged.
- **Safe Auto** starts at exactly **one photo frame in flight**. If RSSI is known and >= -73 dBm and at least 9 clean ACKs have occurred with averaged latency <= 900 ms, it can increase to **two** frames. With stronger RSSI (>= -58 dBm), 28 clean ACKs, and average latency <= 500 ms, it can go to **three** frames.
- Any failed fragment, retry, slow ACK > 1900 ms, or rising firmware TX failure counter stops new parallel sends and reduces the window to one, with a conservative cooldown. Already-in-flight fragments are allowed to finish. Poor/unknown RSSI also limits it to one.
- **Text and control receipts always use the original one-fragment path**, regardless of settings.
- Settings -> Photo transfer throughput -> **Stable** immediately stops launching new parallel fragments (already-in-flight ones finish). It is a browser-only rollback; no reflashing required.
- The ESP-NOW **radio PHY is unchanged at the proven stable 1 Mbps**. This build adapts *effective photo data throughput*, not the radio PHY. Changing PHY on a broadcast link requires a separately validated peer-aware transport and should not be mixed with the stable-release branch.

## Run

```bash
python3 -m http.server 8765 --directory web
```

Open `http://localhost:8765/?v=1.4.6`, hard refresh, connect both existing flashed ESP32-C3 nodes, and ensure both are paired with the same node-stored key.

## Test sequence

1. Confirm `READY`, idle without watchdog warnings, and quick text delivery in **both** directions.
2. Send 10 texts to confirm text behavior unchanged.
3. Transfer a 5 KB and then a 30–50 KB photo at close range. Observe `photo window 1`, then `2`, eventually `3` only if signal and ACKs are consistently excellent. Compare observed KB/s and times against Stable mode.
4. Move the boards farther apart (or add obstacles) and watch photo window reduce to one if RSSI weakens or ACKs fail.
5. Disconnect/reconnect, test text and read receipts again. Use Settings -> Stable immediately if photo transfers regress. The frozen firmware baseline remains intact.

**Caveats:** RSSI is measured from received packets and is not a direct distance measurement; the displayed 1 Mbps is a PHY setting, not application throughput. Browser transfer data is encrypted; radio is still the original reliable broadcast design. Encryption keys are fetched from each ESP32 over the physical USB connection. Stored NVS data is not automatically resistant to flash extraction; do not treat this hardware as tamper-proof.

## Source checks

```bash
node --check web/adaptive.js
node --check web/app.js
node tests/test-adaptive.cjs
node tests/test-protocol.cjs
python3 tests/test-watchdog-fix.py
python3 tests/test-stable-contract.py
```

Hardware performance must be measured using the real two-board setup. No ESP-IDF toolchain or boards are available in this environment.
