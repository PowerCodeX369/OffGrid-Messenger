#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cc -std=c11 -Wall -Wextra -Werror tests/test_stable_rate_policy.c -o "$tmp/stable"; "$tmp/stable"
cc -std=c11 -Wall -Wextra -Werror tests/test_rate_idle_simulation.c -o "$tmp/idle"; "$tmp/idle"
cc -std=c11 -Wall -Wextra -Werror tests/test-lr-policy.c -o "$tmp/old_lr"; "$tmp/old_lr"
cc -std=c11 -Wall -Wextra -Werror tests/test_phy_policy.c -o "$tmp/old_phy"; "$tmp/old_phy"
python3 tests/test_rate_stability_contract.py
cc -std=gnu11 -Wno-implicit-function-declaration -fsyntax-only -I tests/host_stubs -I main -include tests/host_stubs/stub_idf.h main/main.c
cc -std=gnu11 -fsyntax-only -Wno-implicit-function-declaration -Wno-int-conversion -DportMAX_DELAY=99999 -I tests/ble_stubs -I tests/psa_stub -I tests/host_stubs -I main -include tests/host_stubs/stub_idf.h main/ble_bridge.c
cc -std=c11 -Wall -Wextra -Werror -I main tests/test_ble_binary.c -o "$tmp/bin"; "$tmp/bin"
bash tests/run_psa_hmac_test.sh
python3 scripts/check_partitions.py
python3 tests/test-watchdog-fix.py
python3 tests/test-selective-ack-firmware.py
node tests/test-protocol.cjs
node tests/test-selective-ack.cjs
printf 'PASS: RF stability focused tests and preserved protocol regression tests\n'
