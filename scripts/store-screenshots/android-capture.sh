#!/usr/bin/env bash
# Runs StoreScreenshotTest on the connected emulator and pulls the PNGs.
# Called by .github/workflows/android-screenshots.yml once the emulator is up and
# the debug + androidTest APKs are built.
#
# Usage: android-capture.sh <output dir> <gateway url> [gateway token]
set -euo pipefail

OUT=${1:?output directory}
GATEWAY_URL=${2:?gateway url}
GATEWAY_TOKEN=${3:-}
LOG=${INSTRUMENT_LOG:-$OUT.instrument.log}
DEVICE_DIR=/data/local/tmp/ghostcrab-store-screenshots
TEST_CLASS=com.openclaw.ghostcrab.screenshots.StoreScreenshotTest
TARGET_PACKAGE=com.openclaw.ghostcrab.debug

APP_APK=$(find app/build/outputs/apk/debug -name '*.apk' | head -n 1)
TEST_APK=$(find app/build/outputs/apk/androidTest/debug -name '*.apk' | head -n 1)
if [ -z "$APP_APK" ] || [ -z "$TEST_APK" ]; then
  echo "::error::Debug or androidTest APK missing; build :app:assembleDebug :app:assembleDebugAndroidTest first"
  exit 1
fi
adb install -r -t "$APP_APK"
adb install -r -t "$TEST_APK"

RUNNER=$(adb shell pm list instrumentation | tr -d '\r' \
  | grep -F "(target=$TARGET_PACKAGE)" | sed -E 's/^instrumentation:([^ ]+) .*/\1/' | head -n 1 || true)
if [ -z "$RUNNER" ]; then
  echo "::error::No instrumentation runner targets $TARGET_PACKAGE"
  adb shell pm list instrumentation
  exit 1
fi
echo "Instrumentation runner: $RUNNER"

# Demo-mode status bar: 9:41, full battery, full signal, no notification icons.
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile show -e datatype none -e level 4 -e fully true
demo notifications -e visible false

TOKEN_ARGS=()
if [ -n "$GATEWAY_TOKEN" ]; then TOKEN_ARGS=(-e gatewayToken "$GATEWAY_TOKEN"); fi

adb shell rm -rf "$DEVICE_DIR"
set +e
adb shell am instrument -w \
  -e class "$TEST_CLASS" \
  -e storeScreenshots true \
  -e gatewayUrl "$GATEWAY_URL" \
  "${TOKEN_ARGS[@]}" \
  "$RUNNER" 2>&1 | tee "$LOG"
set -e

mkdir -p "$OUT"
adb pull "$DEVICE_DIR/." "$OUT/" || echo "::warning::Nothing to pull from $DEVICE_DIR"
demo exit || true
ls -la "$OUT"

COUNT=$(find "$OUT" -maxdepth 1 -name '*.png' | wc -l)
echo "Pulled $COUNT screenshot(s)"
if ! grep -q '^OK (1 test)' "$LOG"; then
  echo "::error::StoreScreenshotTest did not pass; see the instrumentation output above"
  exit 1
fi
if [ "$COUNT" -eq 0 ]; then
  echo "::error::The test passed but wrote no screenshots (was it skipped?)"
  exit 1
fi
