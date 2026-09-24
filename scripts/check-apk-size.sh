#!/usr/bin/env bash
# Enforces the APK-size budget from design.md §9.2.
set -euo pipefail
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
SIZE=$(stat -c %s "$APK")
KB=$(( (SIZE + 1023) / 1024 ))
echo "Release APK: $SIZE bytes (${KB} KB) — $APK"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  echo "### Release APK size: ${KB} KB ($SIZE bytes)" >> "$GITHUB_STEP_SUMMARY"
fi
WARN=$((500 * 1024)); FAIL=$((2 * 1024 * 1024))
if (( SIZE > FAIL )); then echo "FAIL: APK exceeds 2 MB"; exit 1; fi
if (( SIZE > WARN )); then echo "WARNING: APK exceeds 500 KB budget"; fi
