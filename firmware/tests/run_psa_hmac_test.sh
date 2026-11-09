#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bin="$(mktemp /tmp/offgrid_psa_hmac.XXXXXX)"
trap 'rm -f "$bin"' EXIT
cc -std=c11 -Wall -Wextra -Werror -Wno-deprecated-declarations -I tests/psa_stub \
   tests/test_ble_hmac_psa.c -lcrypto -o "$bin"
"$bin"
