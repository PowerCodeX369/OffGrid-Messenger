#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tests/test_phase244_theme.py
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
src=app/src/main/java/com/offgrid/messenger
kotlinc "$src/HiddenMessageIds.kt" "$src/VoiceGestureRules.kt" tests/phase242/HiddenGestureTest.kt -include-runtime -d "$TMP/gesture.jar"
java -jar "$TMP/gesture.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/ReceiptStates.kt" tests/phase24/HistoryReceiptTest.kt -include-runtime -d "$TMP/history.jar"
java -jar "$TMP/history.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/MessageMutation.kt" tests/phase25/MutationsTest.kt -include-runtime -d "$TMP/mutation.jar"
java -jar "$TMP/mutation.jar"
kotlinc "$src/BleBinaryCodec.kt" tests/BleBinaryCodecTest.kt -include-runtime -d "$TMP/ble.jar"
java -jar "$TMP/ble.jar"
printf 'PASS Phase 2.4.4 host regression checks (NOT an Android SDK build)\n'
