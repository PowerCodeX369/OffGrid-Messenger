#!/usr/bin/env python3
"""Build OffGrid firmware once, flash two *different* ESP32-C3 serial ports.

Run from the ESP-IDF-enabled VS Code terminal, for example:
    python3 scripts/flash_both.py /dev/ttyACM0 /dev/ttyACM1
This version flashes the two ports in parallel using separate esptool processes,
without triggering two competing IDF builds. NVS is not erased.
"""
import argparse
import importlib.metadata
import os
from pathlib import Path
import shutil
import subprocess
import sys


def esptool_command(build_dir: Path, port: str, baud: int) -> list[str]:
    """Use ESP-IDF's authoritative generated flash arguments; never guess offsets."""
    argfile = next((p for p in (build_dir / "flash_args", build_dir / "flash_project_args") if p.is_file()), None)
    if argfile is None:
        raise RuntimeError("ESP-IDF did not generate build/flash_args or build/flash_project_args")
    major = int(importlib.metadata.version("esptool").split(".")[0])
    cmd = "write-flash" if major >= 5 else "write_flash"
    return [sys.executable, "-m", "esptool", "--chip", "esp32c3", "--port", port,
            "--baud", str(baud), "--before", "default-reset" if major >= 5 else "default_reset",
            "--after", "hard-reset" if major >= 5 else "hard_reset", cmd, "@" + argfile.name]


def main() -> int:
    parser = argparse.ArgumentParser(description="Build once, flash two ESP32-C3 boards concurrently")
    parser.add_argument("board_a", help="Serial port of board A, e.g. /dev/ttyACM0")
    parser.add_argument("board_b", help="Serial port of board B, e.g. /dev/ttyACM1")
    parser.add_argument("--baud", type=int, default=460800)
    parser.add_argument("--sequential", action="store_true", help="Flash one after the other (safe fallback)")
    parser.add_argument("--skip-build", action="store_true", help="Use previously generated build binaries")
    args = parser.parse_args()
    ports = [Path(args.board_a), Path(args.board_b)]
    if len({os.path.realpath(str(p)) for p in ports}) != 2:
        parser.error("Board A and B must refer to two DIFFERENT physical ports")
    for p in ports:
        if not p.exists():
            parser.error(f"Port does not exist: {p}. Check ls /dev/ttyACM* /dev/ttyUSB*")
    base = Path(__file__).resolve().parents[1]
    if not args.skip_build:
        if not shutil.which("idf.py"):
            parser.error("idf.py is not on PATH. Run from the ESP-IDF terminal in VS Code.")
        print("Building firmware once...", flush=True)
        subprocess.run(["idf.py", "build"], cwd=base, check=True)
    build = base / "build"
    commands = [esptool_command(build, str(p), args.baud) for p in ports]
    print("Flashing both ESP32-C3 boards. Close any Web Serial / idf.py monitors first.", flush=True)
    processes = []
    log_files = []
    try:
        for idx, cmd in enumerate(commands):
            logpath = build / f"offgrid_board_{idx+1}_flash.log"
            print(f"Board {idx+1}: {ports[idx]} -> {logpath}", flush=True)
            logfile = logpath.open("w", encoding="utf-8")
            log_files.append(logfile)
            proc = subprocess.Popen(cmd, cwd=build, stdout=logfile, stderr=subprocess.STDOUT)
            processes.append(proc)
            if args.sequential:
                proc.wait()
                if proc.returncode != 0:
                    print(f"Board {idx+1} flash FAILED (see {logpath})", file=sys.stderr)
                    return 1
        codes = [p.wait() for p in processes]
    finally:
        for p in processes:
            if p.poll() is None:
                p.terminate()
        for f in log_files:
            f.close()
    for idx, code in enumerate(codes):
        print(f"Board {idx+1}: {'OK' if code == 0 else 'FAILED'} (exit={code})", flush=True)
        if code != 0:
            logpath = build / f"offgrid_board_{idx+1}_flash.log"
            print(f"See full output: {logpath}", file=sys.stderr)
    return 0 if all(code == 0 for code in codes) else 1


if __name__ == "__main__":
    sys.exit(main())
