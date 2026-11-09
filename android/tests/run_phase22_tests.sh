#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
src=app/src/main/java/com/offgrid/messenger
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaWindowContractTest.kt -include-runtime -d "$temp/contract.jar"
java -jar "$temp/contract.jar"
kotlinc "$src/OgmCodec.kt" "$src/MediaTransferWindow.kt" tests/MediaWindowSpeedModel.kt -include-runtime -d "$temp/model.jar"
java -jar "$temp/model.jar"
python3 tests/test_phase22_contract.py
