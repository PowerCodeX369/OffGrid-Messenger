#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)";trap 'rm -rf "$tmp"' EXIT
cc -std=c11 -Wall -Wextra -Werror -I main tests/test_ble_binary.c -o "$tmp/ble_binary"
"$tmp/ble_binary"
cc -std=gnu11 -Wno-implicit-function-declaration -fsyntax-only -I tests/host_stubs -I main -include tests/host_stubs/stub_idf.h main/main.c
cc -std=gnu11 -fsyntax-only -Wno-implicit-function-declaration -Wno-int-conversion \
  -DportMAX_DELAY=99999 -I tests/ble_stubs -I tests/psa_stub -I tests/host_stubs \
  -I main -include tests/host_stubs/stub_idf.h main/ble_bridge.c
python3 scripts/check_partitions.py
python3 tests/test_phase23_integrity.py
