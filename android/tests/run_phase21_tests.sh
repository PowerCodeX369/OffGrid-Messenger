#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
src=app/src/main/java/com/offgrid/messenger
kotlinc "$src/OgmCodec.kt" "$src/MediaPayload.kt" tests/MediaPayloadJvmTest.kt -include-runtime -d "$work/unit.jar"
java -jar "$work/unit.jar" | grep '^PASS'
kotlinc "$src/OgmCodec.kt" "$src/MediaPayload.kt" tests/MediaInteropEmit.kt -include-runtime -d "$work/emit.jar"
java -jar "$work/emit.jar" > "$work/kotlin_media.txt"
node tests/test_media_web_interop.cjs "$work/kotlin_media.txt"
node tests/emit_web_media.cjs > "$work/web_media.txt"
kotlinc "$src/OgmCodec.kt" "$src/MediaPayload.kt" tests/MediaInteropImport.kt -include-runtime -d "$work/import.jar"
java -jar "$work/import.jar" "$work/web_media.txt"
python3 tests/test_phase21_contract.py
python3 tests/test_phase21_integrity.py
python3 tests/test_ble_latency_contract.py
