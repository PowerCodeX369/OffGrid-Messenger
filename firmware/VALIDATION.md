# Phase 1.4.11.2 validation — status

## Passed on this environment

- JavaScript parsing via Node `--check`.
- AES-256-GCM text/photo and encrypted storage unit tests.
- Selective ACK photo protocol tests, including loss/reordering recovery and tamper rejection.
- Existing manual-LR browser automation at multiple window sizes.
- New RF Lab UI automation: command generation, real/synthetic RSSI display separation, restore, layout sizing.
- LR policy native C unit test; additional RF Lab synthetic rate selection unit test.
- Host-stub C syntax checks with both IRQ and legacy USB modes, with expected stub-related implicit-declaration warnings suppressed.
- Preservation checks for browser crypto and adaptive-photo components; existing LR, USB IRQ, watchdog and NVS contract checks.

## Not validated here

- Full compile using ESP-IDF and native C3 driver headers (SDK not available in this container).
- Firmware flashed/running on either of the user's devices.
- Physical TX power accuracy, power step mapping on the specific Super Mini board, actual weak-signal reception and range.
- Real TX/RX packet-loss measurements at reduced power (run the included benchmark on hardware).

## Controlled rollback

RFTEST does not update NVS and automatically restores settings on lease expiry or reboot. Stable baseline: 1.4.10.2 InterruptUSB, user-verified. Phase 1.4.11.1 ManualLR is the immediate LR branch before this RF experiment.
