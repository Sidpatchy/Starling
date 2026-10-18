#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SUPPORT_VERSION="3.13-b16"
SUPPORT_ARCHIVE="Python-3.13-iOS-support.b16.tar.gz"
SUPPORT_SHA256="22b96f3d0c3663225f078556b036d2ee6ce3ff98c885706585240681335c71ea"
SUPPORT_URL="https://github.com/beeware/Python-Apple-support/releases/download/${SUPPORT_VERSION}/${SUPPORT_ARCHIVE}"
VENDOR_DIR="$ROOT/iosApp/Vendor"
MODULES_DIR="$ROOT/iosApp/Sources/Starling/PythonModules"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

mkdir -p "$VENDOR_DIR" "$MODULES_DIR"
curl --fail --location --silent --show-error "$SUPPORT_URL" -o "$TEMP_DIR/$SUPPORT_ARCHIVE"
if command -v shasum >/dev/null 2>&1; then
  echo "$SUPPORT_SHA256  $TEMP_DIR/$SUPPORT_ARCHIVE" | shasum --check --algorithm 256
else
  echo "$SUPPORT_SHA256  $TEMP_DIR/$SUPPORT_ARCHIVE" | sha256sum --check
fi
tar -xzf "$TEMP_DIR/$SUPPORT_ARCHIVE" -C "$TEMP_DIR"

rm -rf "$VENDOR_DIR/Python.xcframework"
cp -R "$TEMP_DIR/Python.xcframework" "$VENDOR_DIR/Python.xcframework"

mkdir -p "$TEMP_DIR/python-base"
python3 -m pip install --disable-pip-version-check --no-input --no-deps \
  --target "$TEMP_DIR/python-base" \
  "rns==1.5.7" "lxmf==1.2.0" "pyserial==3.5"

cp "$ROOT/composeApp/src/main/python/starling_rns.py" "$TEMP_DIR/python-base/"
cp "$ROOT/composeApp/src/main/python/starling_rrc.py" "$TEMP_DIR/python-base/"
cp "$ROOT/composeApp/src/main/python/starling_lxmf.py" "$TEMP_DIR/python-base/"
cp "$ROOT/composeApp/src/main/python/starling_rns_service.py" "$TEMP_DIR/python-base/"
cp "$ROOT/composeApp/src/main/python/starling_bridge_protocol.py" "$TEMP_DIR/python-base/"
cp "$ROOT/composeApp/src/main/python/starling_identity.py" "$TEMP_DIR/python-base/"
cp "$ROOT/composeApp/src/main/python/starling_ios_runtime.py" "$TEMP_DIR/python-base/"
mkdir -p "$TEMP_DIR/python-base/licenses"
cp "$ROOT/licenses/Reticulum-LICENSE.txt" "$TEMP_DIR/python-base/licenses/"
cp "$ROOT/licenses/LXMF-LICENSE.txt" "$TEMP_DIR/python-base/licenses/"
cp "$ROOT/licenses/PySerial-LICENSE.txt" "$TEMP_DIR/python-base/licenses/"
cp "$TEMP_DIR/Python.xcframework/lib/python3.13/LICENSE.txt" "$TEMP_DIR/python-base/licenses/Python-LICENSE.txt"

PYTHONPATH="$TEMP_DIR/python-base" python3 -S - <<'PY'
import LXMF
import RNS
import serial
from RNS.Cryptography import Provider
import starling_ios_runtime

assert Provider.PROVIDER == Provider.PROVIDER_INTERNAL
assert serial.VERSION == "3.5"
assert starling_ios_runtime.invoke("STATUS") == "STATUS\tSTOPPED"
print("RNS/LXMF bridge smoke passed before adding the iOS PyCA wheels.")
PY
PYTHONPATH="$TEMP_DIR/python-base" python3 -S "$ROOT/packaging/ios/test-embedded-python.py"

bash "$ROOT/packaging/ios/build-ios-cryptography-wheels.sh" "$TEMP_DIR/cryptography-wheels"
for slice in ios-device ios-simulator; do
  if [[ "$slice" == "ios-device" ]]; then
    platform="ios_13_0_arm64_iphoneos"
  else
    platform="ios_13_0_arm64_iphonesimulator"
  fi
  modules_dir="$TEMP_DIR/modules-$slice"
  cffi_download="$TEMP_DIR/cffi-wheel-$slice"
  mkdir -p "$modules_dir" "$cffi_download"
  cp -R "$TEMP_DIR/python-base/." "$modules_dir/"
  python3 -m pip download --disable-pip-version-check --no-input --no-deps --only-binary=:all: \
    --platform "$platform" --implementation cp --python-version 313 --abi cp313 \
    --dest "$cffi_download" "cffi==2.1.1"
  crypto_wheel="$(find "$TEMP_DIR/cryptography-wheels" -maxdepth 1 -type f -name "cryptography-*-${platform}.whl" -print -quit)"
  cffi_wheel="$(find "$cffi_download" -maxdepth 1 -type f -name "cffi-*-${platform}.whl" -print -quit)"
  if [[ -z "$crypto_wheel" || -z "$cffi_wheel" ]]; then
    echo "Missing PyCA/cffi iOS wheels for $platform." >&2
    exit 1
  fi
  unzip -q "$crypto_wheel" -d "$modules_dir"
  unzip -q "$cffi_wheel" -d "$modules_dir"
  python3 -m pip install --disable-pip-version-check --no-input --no-deps \
    --target "$modules_dir" "pycparser==2.23"
  test -f "$modules_dir/cryptography/__init__.py"
  test -f "$modules_dir/cffi/__init__.py"
  test -f "$modules_dir/pycparser/__init__.py"
  if ! find "$modules_dir/cryptography" -name '_rust*.so' -print -quit | grep -q .; then
    echo "PyCA cryptography's native Rust extension is missing for $platform." >&2
    exit 1
  fi
  if ! find "$modules_dir" -name '_cffi_backend*.so' -print -quit | grep -q .; then
    echo "CFFI's native extension is missing for $platform." >&2
    exit 1
  fi

  rm -rf "$MODULES_DIR/$slice"
  mkdir -p "$MODULES_DIR/$slice"
  cp -R "$modules_dir/." "$MODULES_DIR/$slice/"
done

echo "Prepared device and simulator Python modules with PyCA cryptography."
