#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 || ! -d "$1" || ! -f "$1/Info.plist" ]]; then
  echo "Usage: $0 <ComposeApp.framework> <debug|release> <output-base.ipa>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FRAMEWORK="$(cd "$1" && pwd)"
CONFIGURATION="$2"
OUTPUT_IPA="$3"
[[ "$OUTPUT_IPA" = /* ]] || OUTPUT_IPA="$ROOT/$OUTPUT_IPA"

case "$CONFIGURATION" in
  debug|release) ;;
  *) echo "Configuration must be debug or release." >&2; exit 2 ;;
esac
[[ "$(uname -s)" == Linux ]] || {
  echo "This helper stages the xcross device framework for an xtool build on Linux." >&2
  exit 1
}
[[ -f "$ROOT/iosApp/Vendor/Python.xcframework/ios-arm64/Python.framework/Python" ]] || {
  echo "Python.xcframework device framework missing; run packaging/ios/prepare-python-runtime.sh first." >&2
  exit 1
}
[[ -d "$ROOT/iosApp/Sources/Starling/PythonModules/ios-device" ]] || {
  echo "Device Python modules missing; run packaging/ios/prepare-python-runtime.sh first." >&2
  exit 1
}
command -v xtool >/dev/null 2>&1 || { echo "xtool is required on PATH." >&2; exit 1; }

XCFRAMEWORK="$ROOT/composeApp/build/XCFrameworks/$CONFIGURATION/ComposeApp.xcframework"
DEVICE_FRAMEWORK="$XCFRAMEWORK/ios-arm64/ComposeApp.framework"
if [[ -e "$XCFRAMEWORK" ]]; then
  [[ -f "$DEVICE_FRAMEWORK/ComposeApp" ]] || {
    echo "Existing Compose XCFramework is incomplete: $XCFRAMEWORK" >&2
    exit 1
  }
  if ! cmp -s "$FRAMEWORK/ComposeApp" "$DEVICE_FRAMEWORK/ComposeApp"; then
    echo "Refusing to replace the existing Compose XCFramework: $XCFRAMEWORK" >&2
    echo "Remove that generated directory explicitly, then rerun this command." >&2
    exit 1
  fi
else
  mkdir -p "$XCFRAMEWORK/ios-arm64"
  cp -R "$FRAMEWORK" "$DEVICE_FRAMEWORK"
  cat > "$XCFRAMEWORK/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>AvailableLibraries</key>
  <array>
    <dict>
      <key>LibraryIdentifier</key><string>ios-arm64</string>
      <key>LibraryPath</key><string>ComposeApp.framework</string>
      <key>SupportedArchitectures</key><array><string>arm64</string></array>
      <key>SupportedPlatform</key><string>ios</string>
      <key>MinimumOSVersion</key><string>15.0</string>
    </dict>
  </array>
  <key>CFBundlePackageType</key><string>XFWK</string>
  <key>XCFrameworkFormatVersion</key><string>1.0</string>
</dict>
</plist>
PLIST
fi

(cd "$ROOT" && STARLING_COMPOSE_CONFIGURATION="$CONFIGURATION" \
  xtool dev build --configuration "$CONFIGURATION" --triple arm64-apple-ios --ipa)

BASE_IPA="$ROOT/xtool/Starling.ipa"
[[ -f "$BASE_IPA" ]] || { echo "xtool did not produce $BASE_IPA" >&2; exit 1; }
mkdir -p "$(dirname "$OUTPUT_IPA")"
cp "$BASE_IPA" "$OUTPUT_IPA"
echo "Built unsigned xtool base IPA: $OUTPUT_IPA"
echo "Next: packaging/ios/build-full-ipa-linux.sh '$OUTPUT_IPA' <output-full-device.ipa>"
