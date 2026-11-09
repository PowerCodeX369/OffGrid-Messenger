#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tests/test_phase25_contract.py
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
src=app/src/main/java/com/offgrid/messenger
kotlinc "$src/HiddenMessageIds.kt" "$src/VoiceGestureRules.kt" tests/phase242/HiddenGestureTest.kt -include-runtime -d "$TMP/gesture.jar"
java -jar "$TMP/gesture.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/ReceiptStates.kt" tests/phase24/HistoryReceiptTest.kt -include-runtime -d "$TMP/history.jar"
java -jar "$TMP/history.jar"
kotlinc tests/phase24/ChatItemStub.kt "$src/OgmCodec.kt" "$src/HistoryCodec.kt" "$src/MessageMutation.kt" tests/phase25/MutationsTest.kt -include-runtime -d "$TMP/mutations.jar"
java -jar "$TMP/mutations.jar"
kotlinc "$src/BleBinaryCodec.kt" tests/BleBinaryCodecTest.kt -include-runtime -d "$TMP/binary.jar"
java -jar "$TMP/binary.jar"
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaWindowContractTest.kt -include-runtime -d "$TMP/media.jar"
java -jar "$TMP/media.jar"
python3 tests/test_ble_latency_contract.py
printf 'PASS Phase 2.5 focused host checks (NOT a complete Android Gradle build)\n'
