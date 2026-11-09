# OffGrid Messenger — Phase 1.4.5.2 (Watchdog Fix)

## What caused the failure

The diagnostic log reports the Task Watchdog timing out because CPU0's idle task is starved while `dispatch` runs. `pdMS_TO_TICKS(5)` rounds **down to zero** when `CONFIG_FREERTOS_HZ=100` (one tick = 10 ms). The event-loop queue therefore did not actually block while idle. This starved other work.

## Changes (firmware ONLY)

- `xQueueReceive(..., pdMS_TO_TICKS(5) + 1)` ensures a positive queue wait at any typical FreeRTOS tick rate.
- `vTaskDelay(1)` at the end of the dispatcher loop guarantees that the idle task can run even under heavy traffic.
- A boot-version label updated to identify the build.

**Unchanged:** ESP-NOW broadcast frame format, channel 6, 1 Mbps conservative PHY, 160-byte browser fragment size, delivery/read receipts, NVS key commands, AES-256-GCM browser encryption, web UI, photo gallery, and stored chat formats. No change to the browser assets is needed.

## Flash

Flash this project onto **both ESP32-C3 boards** with VS Code ESP-IDF. Select `esp32c3` as target, then Build and Flash. Use standard Flash, **not Erase Flash**, to retain stored pairing keys in NVS.

Optional CLI from ESP-IDF terminal:

```bash
idf.py build
idf.py -p /dev/ttyACM0 flash
```

If using an existing `sdkconfig`, ensure USB Serial/JTAG is selected as the ESP console.

## Browser

Use the **same approved 1.4.5.1 web UI** packaged in `web/`; its code is unchanged. Close old tabs, start the server in this project's root:

```bash
python3 -m http.server 8765 --directory web
```

Open `http://localhost:8765/?v=1.4.5.2`, hard refresh and connect both boards.

## Essential acceptance test

1. Connect each ESP32 via separate USB ports to separate tabs.
2. Open Settings → Radio diagnostics or an ESP-IDF monitor. Verify `READY|...|PROTO=2|CAP=180|STABLE=1|NVSKEY=1`.
3. Leave the boards idle for **more than 30 seconds**; there should be **no more** `task_wdt` or `IDLE (CPU 0)` warnings.
4. Send `Hello` in both directions, checking delivery and read receipts.
5. Send 10 short texts, then a small photo. Larger transfers should only be tested once the fundamentals pass.

**Troubleshooting:** If watchdog warnings remain, preserve the full new `task_wdt` log; a different task could be starving CPU0. Do not disable the task watchdog. Don't share any `KEYDATA|...` lines: they contain the pairing key.

## Tests

```bash
node --check web/app.js
node tests/test-protocol.cjs
python3 tests/test-handshake.py
python3 tests/test-stable-contract.py
python3 tests/test-watchdog-fix.py
```

Source-level tests are not ESP-IDF compilation or physical radio tests. ESP-IDF is not installed in this environment.
