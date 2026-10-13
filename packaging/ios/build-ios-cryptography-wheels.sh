#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <wheel-output-directory>" >&2
  exit 2
fi

OUTPUT_DIR="$1"
[[ "$OUTPUT_DIR" = /* ]] || OUTPUT_DIR="$(pwd)/$OUTPUT_DIR"
CRYPTOGRAPHY_VERSION="50.0.2"
CIBUILDWHEEL_VERSION="3.1.4"
CFFI_VERSION="2.1.1"
CRYPTOGRAPHY_SDIST_URL="https://files.pythonhosted.org/packages/9d/af/182eb91b0df3fe75c4d9f26fe70684569566745f6ba7e5c9c73a862c5252/cryptography-50.0.2.tar.gz"
CRYPTOGRAPHY_SDIST_SHA256="7b46165bb56eb4704e2eaaf86f3c940d19154535d9b0ca7d6d590b04060e00d5"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

if ! command -v rustup >/dev/null 2>&1; then
  echo "Rustup is required to cross-build PyCA cryptography for iOS." >&2
  exit 1
fi
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
python3 -m pip install --disable-pip-version-check --no-input "cibuildwheel==$CIBUILDWHEEL_VERSION"
HOST_BUILD_DEPS="$TEMP_DIR/host-build-deps"
python3 -m pip install --disable-pip-version-check --no-input --target "$HOST_BUILD_DEPS" \
  "cffi==$CFFI_VERSION"
HOST_BUILD_ENV="STARLING_CFFI_HOST_PYTHONPATH=$(printf '%q' "$HOST_BUILD_DEPS")"

curl --fail --location --silent --show-error "$CRYPTOGRAPHY_SDIST_URL" -o "$TEMP_DIR/cryptography-sdist.tar.gz"
if command -v shasum >/dev/null 2>&1; then
  echo "$CRYPTOGRAPHY_SDIST_SHA256  $TEMP_DIR/cryptography-sdist.tar.gz" | shasum --check --algorithm 256
else
  echo "$CRYPTOGRAPHY_SDIST_SHA256  $TEMP_DIR/cryptography-sdist.tar.gz" | sha256sum --check
fi

mkdir -p "$TEMP_DIR/cryptography-source" "$TEMP_DIR/wheels"
tar -xzf "$TEMP_DIR/cryptography-sdist.tar.gz" --strip-components=1 -C "$TEMP_DIR/cryptography-source"
# PyCA's Rust build script runs CFFI's code generator with the cross Python.
# Give that subprocess a host CFFI backend while keeping its iOS sysconfig.
python3 - "$TEMP_DIR/cryptography-source/src/rust/cryptography-cffi/build.rs" <<'PY'
import pathlib
import sys

build_script = pathlib.Path(sys.argv[1])
source = build_script.read_text(encoding="utf-8")
original = '    let output = Command::new(&python)\n        .env("OUT_DIR", &out_dir)\n'
replacement = (
    '    let output = Command::new(&python)\n'
    '        .env("PYTHONPATH", env::var("STARLING_CFFI_HOST_PYTHONPATH")\n'
    '            .expect("STARLING_CFFI_HOST_PYTHONPATH must be set for iOS builds"))\n'
    '        .env("OUT_DIR", &out_dir)\n'
)
if source.count(original) != 1:
    raise SystemExit("Unexpected PyCA build.rs; refusing to apply the CFFI cross-build patch.")
build_script.write_text(source.replace(original, replacement), encoding="utf-8")
PY
cat > "$TEMP_DIR/cryptography-source/ios_crypto_smoke.py" <<'PY'
import cffi
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from RNS.Cryptography import Provider

assert cffi.__version__ == "2.1.1"
assert cffi.FFI()
assert Provider.PROVIDER == Provider.PROVIDER_PYCA, Provider.backend()

alice = x25519.X25519PrivateKey.generate()
bob = x25519.X25519PrivateKey.generate()
assert alice.exchange(bob.public_key()) == bob.exchange(alice.public_key())

signing_key = ed25519.Ed25519PrivateKey.generate()
signature = signing_key.sign(b"Starling iOS PyCA smoke test")
signing_key.public_key().verify(signature, b"Starling iOS PyCA smoke test")

key = b"\x01" * 32
iv = b"\x02" * 16
cipher = Cipher(algorithms.AES(key), modes.CBC(iv))
encryptor = cipher.encryptor()
ciphertext = encryptor.update(b"\x10" * 16) + encryptor.finalize()
decryptor = cipher.decryptor()
assert decryptor.update(ciphertext) + decryptor.finalize() == b"\x10" * 16
print("RNS selected PyCA; X25519, Ed25519, and AES iOS smoke tests passed")
PY
(
  cd "$TEMP_DIR/cryptography-source"
  CIBW_BUILD="cp313-ios_arm64_iphoneos cp313-ios_arm64_iphonesimulator" \
  CIBW_ENVIRONMENT="$HOST_BUILD_ENV" \
  CIBW_TEST_COMMAND="python -m ios_crypto_smoke" \
  CIBW_TEST_REQUIRES="cffi==$CFFI_VERSION rns==1.5.7" \
  CIBW_TEST_SOURCES="ios_crypto_smoke.py" \
  CIBW_XBUILD_TOOLS="cargo rustc rustup" \
  python3 -m cibuildwheel \
    --platform ios \
    --archs arm64_iphoneos,arm64_iphonesimulator \
    --output-dir "$TEMP_DIR/wheels" .
)

device_wheel="$(find "$TEMP_DIR/wheels" -maxdepth 1 -type f -name "cryptography-${CRYPTOGRAPHY_VERSION}-*-ios_*_arm64_iphoneos.whl" -print -quit)"
simulator_wheel="$(find "$TEMP_DIR/wheels" -maxdepth 1 -type f -name "cryptography-${CRYPTOGRAPHY_VERSION}-*-ios_*_arm64_iphonesimulator.whl" -print -quit)"
if [[ -z "$device_wheel" || -z "$simulator_wheel" ]]; then
  echo "Cibuildwheel did not produce both iOS cryptography wheels." >&2
  ls -la "$TEMP_DIR/wheels" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
cp "$device_wheel" "$simulator_wheel" "$OUTPUT_DIR/"
