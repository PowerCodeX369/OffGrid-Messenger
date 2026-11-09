# OffGrid Messenger — Phase 1.4.10 USB Input Throughput

**Experimental performance branch.** Based directly on **Phase 1.4.9.1 CompileFix** (measured 1.18 KiB/s browser-to-ESP32 USB; 109.01 KiB/s reverse; 15.15 KiB/s radio-only; 0.92 KiB/s encrypted end-to-end). The **Phase 1.4.5.2 WatchdogFix** remains frozen and untouched.

## Exact firmware change

- `serial_task()` no longer reads one character with `getchar()` and sleeps for 20 ms after empty reads. It now processes up to **256 USB console bytes at a time** with `read(STDIN_FILENO, ...)`, retaining partial commands until `\n` and correctly handling multiple commands in one batch.
- On an empty console read, it yields one FreeRTOS tick (`vTaskDelay(1)`) to avoid starving CPU0 idle, which previously caused watchdog issues. Even under continuous incoming data, it yields after four read batches to keep the task watchdog healthy.
- If an overlong command arrives, bytes are discarded to its terminating newline rather than being misinterpreted as new commands.
- `READY` includes an additional optional `USBRX=BATCH256` field. All existing protocol-2 fields remain identical.
- No changes to radio firmware logic, 1/6/12 Mbps PHY adaptation, photo fragmentation, ACKs, browser-side AES-256-GCM, NVS key persistence, or the UI.

**Why not replace the USB console driver?** ESP-IDF's standard USB Serial/JTAG VFS performs nonblocking console reads. The known bottleneck was repeated 20 ms sleeps between small chunks. This build removes that specific delay and keeps the already proven fast USB download path intact. Installing a second USB driver alongside the VFS could cause a hardware conflict, while switching the entire VFS driver could change TX behavior. An interrupt-driven redesign can be evaluated *separately* only if this low-risk change is insufficient. See Espressif's ESP32-C3 Standard I/O documentation.

## Installation (both boards)

1. Open project in VS Code/ESP-IDF, set target `esp32c3`. Build and flash both Super Minis. **Do not choose Erase Flash**; existing pairing keys are in NVS.
2. Close ESP-IDF serial monitors and the previous localhost web server. From this project folder, run:
   `python3 -m http.server 8765 --directory web`
3. Open `http://localhost:8765/?v=1.4.10` in two Chromium tabs. Hard-refresh both and connect one board per tab.
4. Test a few encrypted short messages and double ticks **before** diagnostics.
5. Settings → Speed diagnostics → Run all tests. Keep the other board's browser connected for the full encrypted-path test.
6. Compare results directly with the previous 1.4.9.1 hardware measurements. Repeat in the reverse direction if desired.

Expected `READY` includes: `PROTO=2|CAP=180|STABLE=1|NVSKEY=1|ADAPTIVEPHY=1|DIAG=1|USBRX=BATCH256`.

## What success looks like

The goal is to improve the measured **USB PC → ESP32** speed from **1.18 KiB/s**, and then see whether **full encrypted browser-to-browser** rises beyond **0.92 KiB/s**. The radio-only test should remain around its previously observed level, although results vary. No new throughput is guaranteed before physical testing.

## Tests run here

- Browser JS syntax and previous encryption/protocol tests
- Existing firmware diagnostic contracts
- Host-side C syntax check using the provided ESP-IDF header stubs (not a full ESP-IDF compile)
- Additional source-level regression assertions: bounded USB batches, newline reassembly, preserved outgoing radio/crypto code, no `getchar()` or 20 ms fallback
- ZIP extraction/integrity

**Not tested in this environment:** ESP-IDF build, USB connection to your boards, radio traffic, or real throughput. If the firmware fails to compile, paste the full compiler error.

## Rollback

To restore the exact previous diagnostic behavior, flash Phase 1.4.9.1. To return to the frozen stable system, flash Phase 1.4.5.2 and use its bundled web app. Never use "Erase Flash" for rollback, since it would delete keys.
