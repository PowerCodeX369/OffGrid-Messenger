# Phase 2.0.6 — app partition overflow fix (ESP-IDF 6.1)

## Why the build stopped
The actual ESP-IDF 6.1 compilation and linking **succeeded**. The binary size is
`0x108740` = 1,083,200 bytes. The factory partition was `0x100000` = 1,048,576
bytes; overflow `0x8740` = 34,624 bytes. The reported Kconfig `default 0`
messages are nonfatal warnings inside ESP-IDF.

## What changed
- `partitions.csv`: standard NVS (`0x9000`/`0x6000`), PHY (`0xF000`/`0x1000`), factory app (`0x10000`/`0x200000`).
- `sdkconfig.defaults`: selects this custom partition table for **fresh** projects.
- `scripts/verify_actual_idf61_build.sh`: fails early if **existing** `sdkconfig` still points to the 1 MiB table.
- `scripts/check_partitions.py`: independent sanity check.

No `.c`, `.h`, Android, encryption, browser or existing ESP-NOW code was modified.

## Hardware requirement
This layout expects **at least 4 MiB physical flash** (ends at `0x210000`).
Check each ESP32-C3 before flashing if its flash capacity is uncertain.
Do not flash to a board with only 2 MiB flash; ask for the alternate 1.5 MiB layout.

## Fresh folder / clean configuration
From the **ESP-IDF 6.1 extension terminal** in `firmware/`:

```bash
idf.py set-target esp32c3
idf.py reconfigure
idf.py partition-table
idf.py build
```

The partition table printout MUST show `factory, app, factory, 0x10000, 2M`.

### If continuing a previously built folder
`sdkconfig.defaults` does not force an existing `sdkconfig` to change. Open:

```bash
idf.py menuconfig
```

Choose **Partition Table → Custom partition table CSV**, set file to
`partitions.csv`, save and exit. Then:

```bash
idf.py reconfigure
idf.py partition-table
idf.py build
```

Alternatively use `bash scripts/verify_actual_idf61_build.sh` to run the full
real-build verification and catch stale configuration.

## Protect pairing keys
- `NVS` is kept at exactly `0x9000` with size `0x6000`, matching the standard
  single-app table. Existing NVS data SHOULD survive a normal flash when the
  prior layout was the standard default. Back up keys where possible.
- **Never choose erase-flash / flash erase**, or change NVS offset/size.
- Do not flash either board until the real build succeeds and flash size verified.
- First flash **one** board, test BLE with the Android app, then the second.

## Flash capacity check (ESP-IDF 6.1 / esptool v5)
Find the ports: `ls /dev/ttyACM* /dev/ttyUSB*`.
A typical query is `python -m esptool --chip esp32c3 --port /dev/ttyACM0 flash-id`.
Use the installed esptool's help if subcommand spelling differs.

## Remaining caveat
The build and partition-size result in your log are proof that the previous
firmware source *compiled and linked*, but I cannot run ESP-IDF locally. The
new partition table must still be generated and checked by your real SDK.
