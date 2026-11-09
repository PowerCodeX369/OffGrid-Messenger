#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tests/test_phase252_contract.py
TEMP=$(mktemp -d); trap 'rm -rf "$TEMP"' EXIT
SRC=app/src/main/java/com/offgrid/messenger
kotlinc tests/phase24/ChatItemStub.kt "$SRC/OgmCodec.kt" "$SRC/ConversationUiRules.kt" tests/phase251_ChatRulesTest.kt -include-runtime -d "$TEMP/chat.jar"
java -jar "$TEMP/chat.jar"
kotlinc "$SRC/BleBinaryCodec.kt" tests/BleBinaryCodecTest.kt -include-runtime -d "$TEMP/binary.jar"
java -jar "$TEMP/binary.jar"
kotlinc "$SRC/OgmCodec.kt" "$SRC/MediaTransferWindow.kt" tests/MediaWindowContractTest.kt -include-runtime -d "$TEMP/media.jar"
java -jar "$TEMP/media.jar"
kotlinc "$SRC/HiddenMessageIds.kt" "$SRC/VoiceGestureRules.kt" tests/phase242/HiddenGestureTest.kt -include-runtime -d "$TEMP/gesture.jar"
java -jar "$TEMP/gesture.jar"
kotlinc tests/phase24/ChatItemStub.kt "$SRC/OgmCodec.kt" "$SRC/HistoryCodec.kt" "$SRC/ReceiptStates.kt" tests/phase24/HistoryReceiptTest.kt -include-runtime -d "$TEMP/history.jar"
java -jar "$TEMP/history.jar"
python3 tests/test_ble_latency_contract.py
printf 'PASS Phase 2.5.2 focused regression tests (NOT a complete Android build)\n'
