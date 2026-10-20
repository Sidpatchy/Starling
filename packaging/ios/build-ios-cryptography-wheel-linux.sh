#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <wheel-output-directory>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_DIR="$1"
[[ "$OUTPUT_DIR" = /* ]] || OUTPUT_DIR="$(pwd)/$OUTPUT_DIR"
SLICE="$ROOT/iosApp/Vendor/Python.xcframework/ios-arm64"
PLATFORM_CONFIG="$SLICE/platform-config/arm64-iphoneos"
PYTHON="${PYTHON313:-python3.13}"
export PATH="$HOME/.cargo/bin:$PATH"
MATURIN_VERSION="1.15.0"
CRYPTOGRAPHY_VERSION="50.0.2"
OPENSSL_SRC_VERSION="300.6.1+3.6.3"
CRYPTOGRAPHY_SDIST_URL="https://files.pythonhosted.org/packages/9d/af/182eb91b0df3fe75c4d9f26fe70684569566745f6ba7e5c9c73a862c5252/cryptography-50.0.2.tar.gz"
CRYPTOGRAPHY_SDIST_SHA256="7b46165bb56eb4704e2eaaf86f3c940d19154535d9b0ca7d6d590b04060e00d5"
PERL_SOURCE_URL="https://www.cpan.org/src/5.0/perl-5.42.3.tar.gz"
PERL_SOURCE_SHA256="1137740985837b5cdf15f0cfab932279dcb4352f912fed6fc144e8b4f0823627"

fail() {
  echo "Error: $*" >&2
  exit 1
}

[[ "$(uname -s)" == Linux ]] || fail "this builder is intended for Linux hosts"
[[ -d "$PLATFORM_CONFIG" && -x "$SLICE/bin/arm64-apple-ios-clang" ]] || \
  fail "Python.xcframework device slice missing; run packaging/ios/prepare-python-runtime.sh first"
command -v "$PYTHON" >/dev/null 2>&1 || fail "CPython 3.13 is required (set PYTHON313 to its executable)"
command -v xcrun >/dev/null 2>&1 || fail "xcross xcrun is required on PATH"
command -v rustup >/dev/null 2>&1 || fail "rustup is required on PATH"
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v tar >/dev/null 2>&1 || fail "tar is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"

mkdir -p "$OUTPUT_DIR"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/starling-ios-pyca-linux.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

HOST_PYTHON="$("$PYTHON" -c 'import sys; print(sys.executable)')"
HOST_VENV="$WORK_DIR/host-venv"
CROSS_VENV="$WORK_DIR/cross-venv"
HOST_CFFI="$WORK_DIR/host-cffi"
PERL_MODULES="$WORK_DIR/perl-modules"
TOOL_BIN="$WORK_DIR/tool-bin"
SOURCE="$WORK_DIR/cryptography-source"
WHEELS="$WORK_DIR/wheels"

"$HOST_PYTHON" -m venv "$HOST_VENV"
"$HOST_PYTHON" -m venv "$CROSS_VENV"
"$HOST_PYTHON" "$PLATFORM_CONFIG/make_cross_venv.py" "$CROSS_VENV" "$PLATFORM_CONFIG"
"$HOST_VENV/bin/pip" install --disable-pip-version-check --no-input \
  "maturin==$MATURIN_VERSION"
"$HOST_PYTHON" -m pip install --disable-pip-version-check --no-input \
  --target "$HOST_CFFI" 'cffi==2.1.1'

rustup target add aarch64-apple-ios

mkdir -p "$PERL_MODULES" "$TOOL_BIN" "$SOURCE" "$WHEELS"
curl --fail --location --retry 3 --silent --show-error "$PERL_SOURCE_URL" \
  -o "$WORK_DIR/perl-source.tar.gz"
echo "$PERL_SOURCE_SHA256  $WORK_DIR/perl-source.tar.gz" | sha256sum --check

extract_perl_module() {
  local member="$1" strip="$2"
  tar -xzf "$WORK_DIR/perl-source.tar.gz" -C "$PERL_MODULES" \
    --strip-components="$strip" "$member"
}

extract_perl_module perl-5.42.3/dist/FindBin/lib/FindBin.pm 4
extract_perl_module perl-5.42.3/lib/File/Copy.pm 2
extract_perl_module perl-5.42.3/cpan/IPC-Cmd/lib/IPC/Cmd.pm 4
extract_perl_module perl-5.42.3/cpan/Params-Check/lib/Params/Check.pm 4
extract_perl_module perl-5.42.3/cpan/Module-Load-Conditional/lib/Module/Load/Conditional.pm 4
extract_perl_module perl-5.42.3/cpan/Locale-Maketext-Simple/lib/Locale/Maketext/Simple.pm 4
extract_perl_module perl-5.42.3/cpan/Module-Load/lib/Module/Load.pm 4
extract_perl_module perl-5.42.3/cpan/Module-Metadata/lib/Module/Metadata.pm 4
extract_perl_module perl-5.42.3/cpan/version/lib/version.pm 4
extract_perl_module perl-5.42.3/cpan/version/lib/version/regex.pm 4
extract_perl_module perl-5.42.3/dist/Locale-Maketext/lib/Locale/Maketext.pm 4
extract_perl_module perl-5.42.3/ext/File-Find/lib/File/Find.pm 4
extract_perl_module perl-5.42.3/lib/File/Compare.pm 2

mkdir -p "$PERL_MODULES/ExtUtils" "$PERL_MODULES/Time"
cat > "$PERL_MODULES/ExtUtils/MakeMaker.pm" <<'PERL'
package ExtUtils::MakeMaker;
use strict;
use warnings;
package MM;
use strict;
use warnings;
sub maybe_command {
    my ($self, $file) = @_;
    return unless defined $file && length $file;
    return $file if -x $file && ! -d $file;
    return;
}
1;
PERL
cat > "$PERL_MODULES/Time/Piece.pm" <<'PERL'
package Time::Piece;
use strict;
use warnings;
require Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(localtime);
sub localtime {
    my $epoch = @_ ? shift : CORE::time();
    my @parts = CORE::localtime($epoch);
    return bless { year => $parts[5] + 1900, month => $parts[4] + 1, day => $parts[3] }, __PACKAGE__;
}
sub strptime {
    my ($class, $value, $format) = @_;
    die "unsupported Time::Piece strptime format: $format" unless $format eq '%d %b %Y';
    my %months = (Jan => 1, Feb => 2, Mar => 3, Apr => 4, May => 5, Jun => 6, Jul => 7, Aug => 8, Sep => 9, Oct => 10, Nov => 11, Dec => 12);
    my ($day, $month, $year) = $value =~ /^\s*(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})\s*$/;
    die "invalid date for Time::Piece: $value" unless $day && $months{$month};
    return bless { year => $year, month => $months{$month}, day => $day }, $class;
}
sub strftime {
    my ($self, $format) = @_;
    $format =~ s/%Y/sprintf('%04d', $self->{year})/ge;
    $format =~ s/%m/sprintf('%02d', $self->{month})/ge;
    $format =~ s/%d/sprintf('%02d', $self->{day})/ge;
    return $format;
}
1;
PERL

cat > "$TOOL_BIN/arm64-apple-ios-clang" <<'SH'
#!/bin/sh
exec xcrun --sdk iphoneos${IOS_SDK_VERSION:?} clang \
  -target arm64-apple-ios${IPHONEOS_DEPLOYMENT_TARGET:?} -fuse-ld=lld "$@"
SH
cat > "$TOOL_BIN/arm64-apple-ios-clang++" <<'SH'
#!/bin/sh
exec xcrun --sdk iphoneos${IOS_SDK_VERSION:?} clang++ \
  -target arm64-apple-ios${IPHONEOS_DEPLOYMENT_TARGET:?} -fuse-ld=lld "$@"
SH
chmod +x "$TOOL_BIN/arm64-apple-ios-clang" "$TOOL_BIN/arm64-apple-ios-clang++"

curl --fail --location --retry 3 --silent --show-error "$CRYPTOGRAPHY_SDIST_URL" \
  -o "$WORK_DIR/cryptography-sdist.tar.gz"
echo "$CRYPTOGRAPHY_SDIST_SHA256  $WORK_DIR/cryptography-sdist.tar.gz" | sha256sum --check
tar -xzf "$WORK_DIR/cryptography-sdist.tar.gz" --strip-components=1 -C "$SOURCE"
"$HOST_PYTHON" "$ROOT/packaging/ios/patch-cryptography-ios-build.py" "$SOURCE"

cargo update --manifest-path "$SOURCE/src/rust/Cargo.toml" --package openssl-sys
cargo update --manifest-path "$SOURCE/src/rust/Cargo.toml" \
  --package openssl-src --precise "$OPENSSL_SRC_VERSION"

SDKROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
export SDKROOT
export IPHONEOS_DEPLOYMENT_TARGET=13.0
IOS_SDK_VERSION="$(xcrun --sdk iphoneos --show-sdk-version)"
export IOS_SDK_VERSION
export PERL5LIB="$PERL_MODULES"
export PATH="$TOOL_BIN:$SLICE/bin:$CROSS_VENV/bin:$HOME/.cargo/bin:$HOST_VENV/bin:/usr/local/bin:/usr/bin:/bin"
export CC=arm64-apple-ios-clang
export CXX=arm64-apple-ios-clang++
export AR=arm64-apple-ios-ar
export CC_aarch64_apple_ios=arm64-apple-ios-clang
export CXX_aarch64_apple_ios=arm64-apple-ios-clang++
export AR_aarch64_apple_ios=arm64-apple-ios-ar
export CFLAGS='-fno-strict-overflow -Wsign-compare -Wunreachable-code -fno-common -dynamic -DNDEBUG -O3 -Wall -mios-version-min=13.0'
export CARGO_TARGET_AARCH64_APPLE_IOS_LINKER=arm64-apple-ios-clang
export PYO3_CROSS=1
export PYO3_CROSS_PYTHON_VERSION=3.13
export STARLING_CFFI_HOST_PYTHONPATH="$HOST_CFFI"
PYO3_CROSS_LIB_DIR="$("$CROSS_VENV/bin/python" "$ROOT/packaging/ios/prepare-pyo3-cross-lib.py")"
export PYO3_CROSS_LIB_DIR

cd "$SOURCE"
"$HOST_VENV/bin/maturin" build \
  --manifest-path src/rust/Cargo.toml \
  --target aarch64-apple-ios \
  --interpreter "$CROSS_VENV/bin/python" \
  --release \
  --out "$WHEELS" \
  --locked

WHEEL="$WHEELS/cryptography-${CRYPTOGRAPHY_VERSION}-cp313-abi3-ios_13_0_arm64_iphoneos.whl"
[[ -f "$WHEEL" ]] || fail "maturin did not produce the expected device wheel"
cp "$WHEEL" "$OUTPUT_DIR/"
echo "Built Linux cross-compiled iOS device wheel: $OUTPUT_DIR/$(basename "$WHEEL")"
