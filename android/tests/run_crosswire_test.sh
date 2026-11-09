#!/usr/bin/env bash
# Real Kotlin <-> browser WebCrypto OGM4 interoperability check.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/offgrid/messenger
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
kotlinc tests/phase24/ChatItemStub.kt "$SRC/OgmCodec.kt" "$SRC/MessageMutation.kt" tests/phase25/CrossWire.kt -include-runtime -d "$TMP/crosswire.jar"
CROSSWIRE_JAR="$TMP/crosswire.jar" node ../firmware/tests/test-phase241-crosswire.cjs
