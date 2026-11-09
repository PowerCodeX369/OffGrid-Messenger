#!/usr/bin/env bash
# Compatibility entrypoint: Phase 2.4 assertions expected versionCode 240.
# Phase 2.4.1 retains the history test and uses the updated preservation contract.
exec "$(dirname "$0")/run_phase241_tests.sh" "$@"
