#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tests/test_phase251_contract.py
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
S=app/src/main/java/com/offgrid/messenger
kotlinc tests/phase24/ChatItemStub.kt "$S/OgmCodec.kt" "$S/ConversationUiRules.kt" tests/phase251_ChatRulesTest.kt -include-runtime -d "$TMP/chat251.jar"
java -jar "$TMP/chat251.jar"
python3 tests/test_ble_latency_contract.py
printf 'PASS: 2.5.1 focused checks (NOT full Android build)\n'
