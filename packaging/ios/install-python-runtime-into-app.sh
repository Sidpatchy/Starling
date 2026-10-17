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
    EFFECTIVE_PLATFORM_NAME="-iphoneos"
    FRAMEWORK_SLICE="ios-arm64"
    PYTHON_MODULE_SLICE="ios-device"
    ;;
  iPhoneSimulator)
    EFFECTIVE_PLATFORM_NAME="-iphonesimulator"
    FRAMEWORK_SLICE="ios-arm64_x86_64-simulator"
    PYTHON_MODULE_SLICE="ios-simulator"
    ;;
  *)
    echo "Unsupported iOS app platform: $PLATFORM" >&2
    exit 1
    ;;
esac

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
