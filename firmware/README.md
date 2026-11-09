# OffGrid Messenger — Phase 1.4.10.1 USB RX Recovery

**Controlled regression repair for the experimental 1.4.10 build.** The confirmed result was that using `read(STDIN_FILENO, rx, 256)` caused USB upload timeout and photo speed ~0.1 KiB/s. Do not continue using 1.4.10.

## Scope

This project is derived **directly from Phase 1.4.9.1 CompileFix**, which successfully measured:

- USB PC → ESP32: **1.18 KiB/s**
- USB ESP32 → PC: **109.01 KiB/s**
- ESP-NOW MCU only: **15.15 KiB/s** (32/32 packets received)
- Encrypted browser-to-browser: **0.92 KiB/s**

It restores the previously functional `getchar()` standard-input parser, all original diagnostic commands and all ESP-NOW logic. The **only functional code change** from 1.4.9.1 is that when stdin is empty, the serial task sleeps for **one FreeRTOS tick instead of `pdMS_TO_TICKS(20)`**.

This is an experimental, conservative polling change, not a guaranteed throughput fix. No new USB drivers, `read()` calls, direct USB FIFO access, task priorities, or radio changes are introduced. The packet protocol, web app, crypto, NVS pairing key, 12 Mbps adaptive PHY, USB `READY` response, and watchdog fix remain byte-for-byte the same as Phase 1.4.9.1.

**Frozen reference baseline remains Phase 1.4.5.2 WatchdogFix.**

## Setup

1. Open this directory in VS Code with ESP-IDF (ESP32-C3 target).
2. Flash both Super Minis normally (do **not** erase flash; NVS stores your pairing keys).
3. Ensure serial monitors are closed before connecting the browser.
4. From the project folder, run `python3 -m http.server 8765 --directory web`.
5. Open `http://localhost:8765/?v=1.4.9` in two tabs, **hard-refresh both**, and connect the boards.
6. Confirm text and delivery ticks in both directions first. Then open **Settings → Speed diagnostics** and run USB upload, USB download, radio-only, and encrypted full-path benchmarks.

**Why the URL says 1.4.9:** This release deliberately reuses the *exact tested 1.4.9.1 browser client files*. Its scripts and compatibility fields have not been altered. The directory/release name is 1.4.10.1 to distinguish the firmware candidate.

## Interpreting the results

- If USB upload works again, the 1.4.10 raw `read()` path was a regression. Compare its KiB/s against 1.18 KiB/s.
- If upload is still slow, do not increase PHY rate further. We already demonstrated that radio speed and USB download are much higher than USB upload.
- If this recovery candidate still has a problem, flash the **original 1.4.9.1 CompileFix** project (known 1.18 KiB/s) as a direct rollback, preserving NVS.
- Avoid enabling two simultaneous serial clients for one USB port.

## Validation done without hardware

- Firmware diff checked to be only the empty-input delay comment and implementation.
- Web files byte-for-byte verified against Phase 1.4.9.1.
- Browser syntax and encryption tests run.
- Host-side C syntax check with ESP-IDF mock headers run (not an ESP-IDF build).
- ESP-IDF hardware compilation and USB throughput **not** verified here; please measure on both physical boards.

## Security

AES-256-GCM continues to run in the **browser**; the ESP32 keeps its pairing key in NVS and forwards encrypted data. Standard unencrypted NVS storage does not resist physical flash extraction, so keep the boards secure.
