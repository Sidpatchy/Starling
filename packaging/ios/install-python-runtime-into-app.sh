#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -d "$1" ]]; then
  echo "Usage: $0 <Starling.app>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_PATH="$(cd "$1" && pwd)"
PLATFORM="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleSupportedPlatforms:0' "$APP_PATH/Info.plist")"
case "$PLATFORM" in
  iPhoneOS)
    ASSET_PLATFORM="iphoneos"
    EFFECTIVE_PLATFORM_NAME="-iphoneos"
    FRAMEWORK_SLICE="ios-arm64"
    PYTHON_MODULE_SLICE="ios-device"
    ;;
  iPhoneSimulator)
    ASSET_PLATFORM="iphonesimulator"
    EFFECTIVE_PLATFORM_NAME="-iphonesimulator"
    FRAMEWORK_SLICE="ios-arm64_x86_64-simulator"
    PYTHON_MODULE_SLICE="ios-simulator"
    ;;
  *)
    echo "Unsupported iOS app platform: $PLATFORM" >&2
    exit 1
    ;;
esac

ICON_OUTPUT_DIR="$(mktemp -d)"
trap 'rm -rf "$ICON_OUTPUT_DIR"' EXIT
ICON_PARTIAL_INFO_PLIST="$ICON_OUTPUT_DIR/assetcatalog-info.plist"
# xtool accepts one icon PNG; compile the appearance-aware catalog after packaging.
xcrun actool \
  --platform "$ASSET_PLATFORM" \
  --minimum-deployment-target 17.0 \
  --app-icon StarlingAppIcon \
  --target-device iphone \
  --target-device ipad \
  --compile "$ICON_OUTPUT_DIR" \
  --output-partial-info-plist "$ICON_PARTIAL_INFO_PLIST" \
  "$ROOT/iosApp/Resources/Assets.xcassets"
if [[ ! -f "$ICON_OUTPUT_DIR/Assets.car" || ! -f "$ICON_PARTIAL_INFO_PLIST" ]]; then
  echo "Xcode did not produce the Starling app icon asset catalog." >&2
  exit 1
fi
cp "$ICON_OUTPUT_DIR/Assets.car" "$APP_PATH/Assets.car"
/usr/libexec/PlistBuddy -c "Merge $ICON_PARTIAL_INFO_PLIST" "$APP_PATH/Info.plist"

if [[ ! -f "$APP_PATH/Frameworks/Python.framework/Python" ]]; then
  mkdir -p "$APP_PATH/Frameworks"
  cp -R "$ROOT/iosApp/Vendor/Python.xcframework/$FRAMEWORK_SLICE/Python.framework" "$APP_PATH/Frameworks/"
fi
if [[ ! -f "$APP_PATH/Frameworks/Python.framework/Python" ]]; then
  echo "The app is missing Python.framework for $PLATFORM." >&2
  exit 1
fi

RESOURCE_BUNDLE="$(find "$APP_PATH" -maxdepth 2 -type d -name 'Starling_Starling.bundle' -print -quit)"
if [[ -z "$RESOURCE_BUNDLE" || ! -d "$RESOURCE_BUNDLE/PythonModules" ]]; then
  echo "The app is missing its Starling_Starling.bundle/PythonModules resources." >&2
  exit 1
fi
if [[ "$PYTHON_MODULE_SLICE" == "ios-device" ]]; then
  rm -rf "$RESOURCE_BUNDLE/PythonModules/ios-simulator"
else
  rm -rf "$RESOURCE_BUNDLE/PythonModules/ios-device"
fi

export PROJECT_DIR="$ROOT"
export CODESIGNING_FOLDER_PATH="$APP_PATH"
export EFFECTIVE_PLATFORM_NAME
export PLATFORM_FAMILY_NAME="iOS"
export ARCHS="arm64"
export PRODUCT_BUNDLE_IDENTIFIER="com.sidpatchy.Starling"
export EXPANDED_CODE_SIGN_IDENTITY="-"
export EXPANDED_CODE_SIGN_IDENTITY_NAME="-"

source "$ROOT/iosApp/Vendor/Python.xcframework/build/utils.sh"
install_python \
  "iosApp/Vendor/Python.xcframework" \
  "${RESOURCE_BUNDLE#"$APP_PATH"/}/PythonModules/$PYTHON_MODULE_SLICE"

if ! find "$APP_PATH/Frameworks" -maxdepth 1 -type d \
  -name 'cryptography.hazmat.bindings._rust.framework' -print -quit | grep -q .; then
  echo "PyCA cryptography's signed Rust framework was not installed." >&2
  exit 1
fi
if ! find "$APP_PATH/Frameworks" -maxdepth 1 -type d \
  -name '_cffi_backend.framework' -print -quit | grep -q .; then
  echo "CFFI's signed backend framework was not installed." >&2
  exit 1
fi
