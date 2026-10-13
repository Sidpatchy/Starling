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

mkdir -p "$TEMP_DIR/python-modules"
python3 -m pip install --disable-pip-version-check --no-input --no-deps \
  --target "$TEMP_DIR/python-modules" \
  "rns==1.5.7" "lxmf==1.2.0"

cp "$ROOT/composeApp/src/main/python/starling_rns.py" "$TEMP_DIR/python-modules/"
cp "$ROOT/composeApp/src/main/python/starling_rrc.py" "$TEMP_DIR/python-modules/"
cp "$ROOT/composeApp/src/main/python/starling_lxmf.py" "$TEMP_DIR/python-modules/"
cp "$ROOT/composeApp/src/main/python/starling_rns_service.py" "$TEMP_DIR/python-modules/"
cp "$ROOT/composeApp/src/main/python/starling_ios_runtime.py" "$TEMP_DIR/python-modules/"
mkdir -p "$TEMP_DIR/python-modules/licenses"
cp "$ROOT/licenses/Reticulum-LICENSE.txt" "$TEMP_DIR/python-modules/licenses/"
cp "$ROOT/licenses/LXMF-LICENSE.txt" "$TEMP_DIR/python-modules/licenses/"
cp "$TEMP_DIR/Python.xcframework/lib/python3.13/LICENSE.txt" "$TEMP_DIR/python-modules/licenses/Python-LICENSE.txt"

cp -R "$TEMP_DIR/python-modules/." "$MODULES_DIR/"
PYTHONPATH="$MODULES_DIR" python3 -S - <<'PY'
import LXMF
import RNS
from RNS.Cryptography import Provider
import starling_ios_runtime

assert Provider.PROVIDER == Provider.PROVIDER_INTERNAL
assert starling_ios_runtime.invoke("STATUS") == "STATUS\tSTOPPED"
print("Bundled RNS/LXMF iOS Python modules are importable (RNS internal crypto provider).")
PY
PYTHONPATH="$MODULES_DIR" python3 -S "$ROOT/packaging/ios/test-embedded-python.py"
