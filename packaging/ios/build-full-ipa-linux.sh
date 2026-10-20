#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <xtool-base.ipa> <output-full-device.ipa>" >&2
  echo "The base IPA must already contain Python.framework, CPython 3.13, and PythonModules/ios-device." >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_IPA="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
OUTPUT_IPA="$2"
[[ "$OUTPUT_IPA" = /* ]] || OUTPUT_IPA="$ROOT/$OUTPUT_IPA"
PYTHON="${PYTHON313:-python3.13}"
WHEEL_DIR="${STARLING_IOS_WHEEL_DIR:-$ROOT/build/ios-linux/wheels}"
CRYPTO_WHEEL="$WHEEL_DIR/cryptography-50.0.2-cp313-abi3-ios_13_0_arm64_iphoneos.whl"

[[ -f "$BASE_IPA" ]] || { echo "Base IPA not found: $BASE_IPA" >&2; exit 1; }
[[ -f "$ROOT/iosApp/Vendor/Python.xcframework/ios-arm64/Python.framework/Python" ]] || {
  echo "Python.xcframework device framework is missing. Run packaging/ios/prepare-python-runtime.sh first." >&2
  exit 1
}
command -v "$PYTHON" >/dev/null 2>&1 || {
  echo "CPython 3.13 is required (set PYTHON313 to its executable)." >&2
  exit 1
}

mkdir -p "$WHEEL_DIR"
if [[ ! -f "$CRYPTO_WHEEL" ]]; then
  bash "$ROOT/packaging/ios/build-ios-cryptography-wheel-linux.sh" "$WHEEL_DIR"
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/starling-ipa-wheels.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

"$PYTHON" -m pip download --disable-pip-version-check --no-input --no-deps \
  --only-binary=:all: \
  --platform ios_13_0_arm64_iphoneos \
  --implementation cp --python-version 313 --abi cp313 \
  --dest "$WORK_DIR" 'cffi==2.1.1'
"$PYTHON" -m pip download --disable-pip-version-check --no-input --no-deps \
  --only-binary=:all: --dest "$WORK_DIR" 'pycparser==2.23'

CFFI_WHEEL="$WORK_DIR/cffi-2.1.1-cp313-cp313-ios_13_0_arm64_iphoneos.whl"
PYCPARSER_WHEEL="$(find "$WORK_DIR" -maxdepth 1 -type f -name 'pycparser-2.23-*.whl' -print -quit)"
[[ -f "$CFFI_WHEEL" && -n "$PYCPARSER_WHEEL" ]] || {
  echo "Could not download the required device cffi and pycparser wheels." >&2
  exit 1
}

"$PYTHON" "$ROOT/packaging/ios/package-full-ipa-linux.py" \
  "$BASE_IPA" "$CRYPTO_WHEEL" "$CFFI_WHEEL" "$PYCPARSER_WHEEL" "$OUTPUT_IPA"
