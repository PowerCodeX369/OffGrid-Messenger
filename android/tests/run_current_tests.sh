#!/usr/bin/env bash
# Current (v2.5.6) Android host tests. Needs kotlinc, java, python3, node.
# Older run_phase2x_tests.sh / test_phase2x_*.py files are frozen historical
# snapshot gates (they pin old versionCodes/baseline ZIPs) and are expected to fail on main.
set -euo pipefail
cd "$(dirname "$0")"
bash run_phase25_tests.sh
bash run_phase256_tests.sh
bash run_crosswire_test.sh
printf 'PASS all current Android host tests\n'
