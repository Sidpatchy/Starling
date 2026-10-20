# Linux arm64 iOS full-payload build record

Date: 2026-10-20

Artifact: `xtool/Starling-full-device.ipa`

SHA-256: `3776c65b12b50b425d2b70dddd96cdabee060c750101dffd48b6336e473ed6ea`

This is a record of the successful device PyCA cross-build and IPA assembly. It is **not** a claim that the IPA is signed, installable, or runtime-tested on an iPhone. It records the concrete inputs and operations used, including temporary host-tool workarounds.

## Result and scope

The IPA contains the arm64 app, Python.framework/CPython 3.13, the Python standard library, RNS/LXMF and Starling bridge modules, PyCA `cryptography==50.0.2`, `cffi==2.1.1`, and `pycparser==2.23`. The successful PyCA wheel was:

```text
/tmp/opencode/starling-pyca-build/wheels/cryptography-50.0.2-cp313-abi3-ios_13_0_arm64_iphoneos.whl
```

The final IPA was 39,250,271 bytes. Its CRC, required contents, 70 extension-framework binaries, `.fwork` placeholders, and `.origin` mappings were checked. The binaries were verified as Mach-O arm64.

The app shell was built earlier in this session with `xtool dev build --triple arm64-apple-ios --ipa`, from the staged xcross `ComposeApp.framework` and the existing Python XCFramework. The finalization below started from that existing `xtool/Starling.ipa`; it did not rebuild the Compose/Swift app from a clean checkout. The original exact shell command that first staged the xcross framework was not retained. The current expected device-slice layout is `composeApp/build/XCFrameworks/debug/ComposeApp.xcframework/ios-arm64/ComposeApp.framework`, as required by the root `Package.swift`.

## Linux build scripts

The repository now has executable Linux tools in addition to this historical build record:

- `packaging/ios/prepare-python-runtime.sh` detects Linux and prepares a **device-only** Python runtime; macOS retains the device + simulator flow. Linux uses `build-ios-cryptography-wheel-linux.sh` instead of macOS-only cibuildwheel.
- `packaging/ios/build-ios-cryptography-wheel-linux.sh <wheel-dir>` independently builds the pinned arm64 iPhoneOS PyCA wheel using CPython 3.13, xcross's iPhoneOS SDK/compiler, Rust, maturin, and temporary Perl source modules. It checks source archive hashes and does not modify the system Perl installation.
- `packaging/ios/build-base-ipa-linux.sh <ComposeApp.framework> <debug|release> <base.ipa>` stages an existing xcross device framework into the layout required by `Package.swift`, then runs xtool to build an unsigned base IPA.
- `packaging/ios/build-full-ipa-linux.sh <base.ipa> <output.ipa>` builds (or reuses) the PyCA wheel, downloads the pinned device CFFI and pure-Python pycparser wheels, converts native extensions into BeeWare-style frameworks, validates arm64 binaries and module mappings, and assembles the full unsigned IPA.

On a Linux host with Python 3.13, Rust/rustup, xcross (`xcrun` and the iPhoneOS SDK), xtool, and normal build utilities installed, the intended sequence is:

```sh
# Prepare Python.xcframework, Python bridge modules, and the arm64 PyCA wheel.
STARLING_IOS_WHEEL_DIR="$PWD/build/ios-linux/wheels" \
  bash packaging/ios/prepare-python-runtime.sh

# Build ComposeApp.framework with the installed xcross Compose toolchain.
xcross compose build --configuration debug --target-platform iphone --verbose

# Use the resulting device framework (the default xcross output in this build is shown).
bash packaging/ios/build-base-ipa-linux.sh \
  build/xcross-ios/ComposeApp.framework debug xtool/Starling.ipa

# Add the CPython native extensions and PyCA to the base app.
bash packaging/ios/build-full-ipa-linux.sh \
  xtool/Starling.ipa xtool/Starling-full-device.ipa
```

`build-base-ipa-linux.sh` refuses to replace a different existing Compose XCFramework; remove that specific generated configuration directory yourself before rebuilding it. The full-IPA step needs a base IPA containing `Python.framework`, CPython 3.13, and the `Starling_Starling.bundle/PythonModules/ios-device` resources. It writes the output separately and does not modify the base IPA. The result remains unsigned and is not an App Store or device-installable build without Apple's signing and asset-catalog tools.

The xcross invocation above depends on a correctly pinned Kotlin/Native toolchain in the local xcross configuration. In the environment used for this record, the attempted `xcross compose build --configuration debug --target-platform iphone --verbose` stopped before compiling with `No pinned SHA-256 digest for Kotlin/Native kotlin-native-prebuilt-2.4.20-linux-x86_64.tar.gz`. The already-built `build/xcross-ios/ComposeApp.framework` was used for the tested IPA, so a clean Linux Compose framework build was not validated here.

For reference, the current device-only XCFramework layout can be reconstructed from the already-built `build/xcross-ios/ComposeApp.framework` with the following commands. This is a reproducible equivalent, **not a claim that these were the exact historical shell commands**:

```sh
XCFRAMEWORK=composeApp/build/XCFrameworks/debug/ComposeApp.xcframework
mkdir -p "$XCFRAMEWORK/ios-arm64"
cp -R build/xcross-ios/ComposeApp.framework "$XCFRAMEWORK/ios-arm64/ComposeApp.framework"
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
xtool dev build --triple arm64-apple-ios --ipa
```

## Why the normal wheel script was bypassed

`bash packaging/ios/prepare-python-runtime.sh` passed its Python support archive checksum and RNS/LXMF smoke checks, then called cibuildwheel 3.1.4. Cibuildwheel stopped before compilation with:

```text
cibuildwheel: error: iOS binaries can only be built on macOS
```

Inspection of `cibuildwheel/platforms/ios.py` showed this is an explicit `sys.platform != "darwin"` guard. It did not establish that the target compiler could not cross-compile. Independent C and Rust probes produced Mach-O arm64 objects with xcross's SDK.

The first direct maturin build then exposed host-tool setup issues: `/usr/bin/perl` lacked `FindBin.pm`, `File::Copy`, `IPC::Cmd`, and other Perl 5.42.3 modules; OpenSSL configuration later needed `ExtUtils::MakeMaker`, `File::Compare`, and `Time::Piece`. Also, the target clang wrapper's default linker selected GNU `ld.bfd` and failed with `unrecognised emulation mode: llvm`. The build succeeded after providing the required Perl modules/helpers in `/tmp` and making the target clang wrappers add `-fuse-ld=lld`.

## PyCA build inputs and host environment

The commands below use these exact paths:

```sh
ROOT="$PWD"
BUILD=/tmp/opencode/starling-pyca-build
HOST_PYTHON=/tmp/opencode/starling-python/cpython-3.13.16-linux-x86_64-gnu/bin/python3.13
VENV=/tmp/opencode/starling-pyca-cross-3.13.16
HOST_VENV=/tmp/opencode/starling-pyca-host-build
SLICE="$ROOT/iosApp/Vendor/Python.xcframework/ios-arm64"
PLATFORM_CONFIG="$SLICE/platform-config/arm64-iphoneos"
```

CPython 3.13.16 and the two host build environments were prepared as follows:

```sh
uv python install 3.13 --install-dir /tmp/opencode/starling-python
"$HOST_PYTHON" -m venv "$VENV"
"$HOST_PYTHON" "$PLATFORM_CONFIG/make_cross_venv.py" "$VENV" "$PLATFORM_CONFIG"

"$HOST_PYTHON" -m venv "$HOST_VENV"
"$HOST_VENV/bin/pip" install --disable-pip-version-check --no-input 'maturin>=1.14.1,<2'
"$HOST_PYTHON" -m pip install --disable-pip-version-check --no-input \
  --target "$BUILD/host-cffi" 'cffi==2.1.1'
```

The converted venv remained runnable on Linux while reporting `sys.platform == "ios"`, `_multiarch == "arm64-iphoneos"`, and `sysconfig.get_platform() == "ios-13.0-arm64-iphoneos"`.

The device cffi wheel was downloaded with:

```sh
python3 -m pip download --disable-pip-version-check --no-deps --only-binary=:all: \
  --platform ios_13_0_arm64_iphoneos --implementation cp --python-version 313 --abi cp313 \
  --dest /tmp/opencode/ios-wheels 'cffi==2.1.1'
```

This produced `cffi-2.1.1-cp313-cp313-ios_13_0_arm64_iphoneos.whl`.

The PyCA sdist was downloaded, checksum-verified, patched, and its source-only lockfile updated with:

```sh
curl --fail --location --silent --show-error \
  'https://files.pythonhosted.org/packages/9d/af/182eb91b0df3fe75c4d9f26fe70684569566745f6ba7e5c9c73a862c5252/cryptography-50.0.2.tar.gz' \
  -o "$BUILD/cryptography-sdist.tar.gz"
echo '7b46165bb56eb4704e2eaaf86f3c940d19154535d9b0ca7d6d590b04060e00d5  '"$BUILD/cryptography-sdist.tar.gz" | sha256sum --check
mkdir -p "$BUILD/cryptography-source" "$BUILD/wheels"
tar -xzf "$BUILD/cryptography-sdist.tar.gz" --strip-components=1 -C "$BUILD/cryptography-source"
python3 packaging/ios/patch-cryptography-ios-build.py "$BUILD/cryptography-source"
source "$HOME/.cargo/env"
cargo update --manifest-path "$BUILD/cryptography-source/src/rust/Cargo.toml" --package openssl-sys
cargo update --manifest-path "$BUILD/cryptography-source/src/rust/Cargo.toml" \
  --package openssl-src --precise '300.6.1+3.6.3'
```

`patch-cryptography-ios-build.py` makes the CFFI build subprocess use host CFFI and enables the `openssl-sys` vendored OpenSSL feature. The Cargo updates were applied only to the extracted `/tmp` sdist.

PyO3's target Python library/header configuration was staged with the repository helper:

```sh
PATH="$SLICE/bin:$VENV/bin:$HOME/.cargo/bin:/usr/local/bin:/usr/bin:/bin" \
  "$VENV/bin/python" "$ROOT/packaging/ios/prepare-pyo3-cross-lib.py"
```

## Temporary Apple compiler wrappers and Perl support

The xcross SDK resolved from `xcrun --sdk iphoneos --show-sdk-path` to:

```text
/var/home/osprey/.config/xcross/swift-sdks/xcross-darwin.artifactbundle/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS27.1.sdk
```

The original BeeWare wrappers select clang through `xcrun`; the Linux host needed LLD explicitly for links. These temporary wrappers were placed first on `PATH` at `/tmp/opencode/starling-apple-toolchain/bin`:

```sh
#!/bin/sh
exec xcrun --sdk iphoneos${IOS_SDK_VERSION} clang -target arm64-apple-ios${IPHONEOS_DEPLOYMENT_TARGET} -fuse-ld=lld "$@"
```

```sh
#!/bin/sh
exec xcrun --sdk iphoneos${IOS_SDK_VERSION} clang++ -target arm64-apple-ios${IPHONEOS_DEPLOYMENT_TARGET} -fuse-ld=lld "$@"
```

These were saved respectively as `arm64-apple-ios-clang` and `arm64-apple-ios-clang++`, then marked executable. `SDKROOT` was set to the path above, with `IPHONEOS_DEPLOYMENT_TARGET=13.0` and `IOS_SDK_VERSION=27.1`.

The Perl source used to satisfy missing system modules was downloaded from Perl 5.42.3:

```sh
curl --fail --location --silent --show-error \
  'https://www.cpan.org/src/5.0/perl-5.42.3.tar.gz' \
  -o /tmp/opencode/perl-5.42.3-source.tar.gz
mkdir -p /tmp/opencode/perl-5.42.3-core-modules/{File,IPC,Params,Module/Load,Locale/Maketext,version,Time,ExtUtils}
```

The exact module extraction commands were:

```sh
SRC=/tmp/opencode/perl-5.42.3-source.tar.gz
MODULES=/tmp/opencode/perl-5.42.3-core-modules
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/dist/FindBin/lib/FindBin.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=2 perl-5.42.3/lib/File/Copy.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/cpan/IPC-Cmd/lib/IPC/Cmd.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/cpan/Params-Check/lib/Params/Check.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/cpan/Module-Load-Conditional/lib/Module/Load/Conditional.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/cpan/Locale-Maketext-Simple/lib/Locale/Maketext/Simple.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/cpan/Module-Load/lib/Module/Load.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/cpan/Module-Metadata/lib/Module/Metadata.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/cpan/version/lib/version.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/cpan/version/lib/version/regex.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/dist/Locale-Maketext/lib/Locale/Maketext.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=4 perl-5.42.3/ext/File-Find/lib/File/Find.pm
tar -xzf "$SRC" -C "$MODULES" --strip-components=2 perl-5.42.3/lib/File/Compare.pm
```

Two more files were local, narrow compatibility helpers because those Perl distributions were absent from the host. `/tmp/opencode/perl-5.42.3-core-modules/ExtUtils/MakeMaker.pm` implemented the Perl 5.42.3 Unix `MM->maybe_command` behavior used by `IPC::Cmd::can_run`:

```perl
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
```

`/tmp/opencode/perl-5.42.3-core-modules/Time/Piece.pm` implemented only the `localtime`, `%d %b %Y` `strptime`, and `%Y-%m-%d` `strftime` behavior used by OpenSSL's `Makefile.in`:

```perl
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
```

These helpers and the extracted Perl modules were used only through `PERL5LIB=/tmp/opencode/perl-5.42.3-core-modules`; `/usr/bin/perl` and system files were not modified.

## Exact successful maturin command

With the above paths, the successful build was run as:

```sh
ROOT="$PWD"
BUILD=/tmp/opencode/starling-pyca-build
SLICE="$ROOT/iosApp/Vendor/Python.xcframework/ios-arm64"
VENV=/tmp/opencode/starling-pyca-cross-3.13.16
HOST_VENV=/tmp/opencode/starling-pyca-host-build
SDKROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
export SDKROOT IPHONEOS_DEPLOYMENT_TARGET=13.0 IOS_SDK_VERSION=27.1
export PERL5LIB=/tmp/opencode/perl-5.42.3-core-modules
export PATH="/tmp/opencode/starling-apple-toolchain/bin:$SLICE/bin:$VENV/bin:$HOME/.cargo/bin:$HOST_VENV/bin:/usr/local/bin:/usr/bin:/bin"
export CC=arm64-apple-ios-clang CXX=arm64-apple-ios-clang++ AR=arm64-apple-ios-ar
export CC_aarch64_apple_ios=arm64-apple-ios-clang CXX_aarch64_apple_ios=arm64-apple-ios-clang++ AR_aarch64_apple_ios=arm64-apple-ios-ar
export CFLAGS='-fno-strict-overflow -Wsign-compare -Wunreachable-code -fno-common -dynamic -DNDEBUG -O3 -Wall -mios-version-min=13.0'
export CARGO_TARGET_AARCH64_APPLE_IOS_LINKER=arm64-apple-ios-clang
export PYO3_CROSS=1 PYO3_CROSS_PYTHON_VERSION=3.13
export PYO3_CROSS_LIB_DIR=/tmp/starling-pyo3-cross/arm64-iphoneos/lib/pyo3-cross
export STARLING_CFFI_HOST_PYTHONPATH="$BUILD/host-cffi"
cd "$BUILD/cryptography-source"
"$HOST_VENV/bin/maturin" build \
  --manifest-path src/rust/Cargo.toml \
  --target aarch64-apple-ios \
  --interpreter "$VENV/bin/python" \
  --release \
  --out "$BUILD/wheels" \
  --locked
```

Result: exit status 0; maturin reported `Finished release profile` and wrote the arm64 iPhoneOS wheel listed above. It emitted a non-fatal warning that an intermediate static archive was in GNU archive format rather than Darwin format. The resulting `_rust.abi3.so` was verified Mach-O arm64; its loaded dylibs are Python.framework and iOS system libraries, with OpenSSL statically linked. The cffi wheel's `_cffi_backend.cpython-313-iphoneos.so` was separately verified Mach-O arm64.

## IPA finalization operations

The following describes the exact finalization performed on the existing base IPA. The extracted working tree was `/tmp/opencode/starling-full-ipa-stage`; the source module root was:

```sh
STAGE=/tmp/opencode/starling-full-ipa-stage
APP="$STAGE/Payload/Starling.app"
MODULES="$APP/Starling_Starling.bundle/PythonModules/ios-device"
CRYPTO_WHEEL=/tmp/opencode/starling-pyca-build/wheels/cryptography-50.0.2-cp313-abi3-ios_13_0_arm64_iphoneos.whl
CFFI_WHEEL=/tmp/opencode/ios-wheels/cffi-2.1.1-cp313-cp313-ios_13_0_arm64_iphoneos.whl
mkdir -p "$STAGE"
unzip -q "$PWD/xtool/Starling.ipa" -d "$STAGE"
```

Each wheel member was extracted beneath `MODULES`, preserving wheel file modes and rejecting paths that escaped that directory. Then pycparser was installed, and stale Python 3.14 bytecode caches were removed:

```sh
python3 - <<'PY' "$MODULES" "$CRYPTO_WHEEL" "$CFFI_WHEEL"
from pathlib import Path
from zipfile import ZipFile
import sys
root = Path(sys.argv[1]).resolve()
for wheel_name in sys.argv[2:]:
    with ZipFile(wheel_name) as wheel:
        for member in wheel.infolist():
            target = (root / member.filename).resolve()
            if target != root and root not in target.parents:
                raise ValueError(f"wheel member escapes target: {member.filename}")
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(wheel.read(member))
                mode = member.external_attr >> 16
                if mode:
                    target.chmod(mode & 0o777)
        print(f"Extracted {wheel_name}")
PY
/tmp/opencode/starling-pyca-host-build/bin/pip install \
  --disable-pip-version-check --no-input --no-deps --only-binary=:all: \
  --target "$MODULES" 'pycparser==2.23'
find "$MODULES" -type d -name __pycache__ -prune -exec rm -rf {} +
find "$MODULES" -type f -name '*.py[co]' -delete
```

The extension postprocess followed the existing BeeWare helper `iosApp/Vendor/Python.xcframework/build/utils.sh`: for each `.so`, it computed the dotted module name relative to the install base, moved the binary into `Frameworks/<module>.framework/<module>`, wrote the template-based `Info.plist`, replaced the `.so` with a `.fwork` pointer, and wrote the corresponding `.origin`. It processed both `python/lib/python3.13/lib-dynload` and `Starling_Starling.bundle/PythonModules/ios-device`.

The exact temporary implementation used for that operation is reproduced here (it also existed at `/tmp/opencode/finalize-starling-ios-ipa.py`):

```python
#!/usr/bin/env python3
"""Stage device PyCA wheels and apply BeeWare's iOS .so-to-framework layout."""
from __future__ import annotations

import plistlib
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: finalize-starling-ios-ipa.py <Starling.app> <Python dylib Info.plist template>")

app = Path(sys.argv[1]).resolve()
template_path = Path(sys.argv[2]).resolve()
with (app / "Info.plist").open("rb") as source:
    app_info = plistlib.load(source)
product_bundle_id = app_info["CFBundleIdentifier"]
with template_path.open("rb") as source:
    template = plistlib.load(source)
frameworks = app / "Frameworks"
frameworks.mkdir(parents=True, exist_ok=True)


def process_tree(tree: Path, install_base: str) -> int:
    converted = 0
    for extension in sorted(tree.rglob("*.so")):
        relative_extension = extension.relative_to(app).as_posix()
        python_extension = relative_extension.replace(install_base, "", 1)
        module_name = python_extension.split(".", 1)[0].replace("/", ".")
        framework_folder = f"Frameworks/{module_name}.framework"
        framework_path = app / framework_folder
        binary_path = framework_path / module_name
        if framework_path.exists():
            raise FileExistsError(f"refusing to overwrite framework: {framework_path}")

        framework_path.mkdir(parents=True)
        info = dict(template)
        info["CFBundleExecutable"] = module_name
        info["CFBundleIdentifier"] = f"{product_bundle_id}.{module_name.replace('_', '-')}"
        with (framework_path / "Info.plist").open("wb") as destination:
            plistlib.dump(info, destination, fmt=plistlib.FMT_XML, sort_keys=False)

        shutil.move(str(extension), str(binary_path))
        binary_path.chmod(binary_path.stat().st_mode | 0o111)
        extension_placeholder = extension.with_name(extension.name[:-3] + ".fwork")
        extension_placeholder.write_text(f"{framework_folder}/{module_name}\n", encoding="utf-8")
        origin = framework_path / f"{module_name}.origin"
        origin.write_text(f"{relative_extension[:-3]}.fwork\n", encoding="utf-8")
        print(f"framework: {framework_folder}")
        converted += 1
    return converted


stdlib = app / "python/lib/python3.13/lib-dynload"
if not stdlib.is_dir():
    raise FileNotFoundError(f"Python 3.13 stdlib extension directory missing: {stdlib}")
stdlib_count = process_tree(stdlib, "python/lib/python3.13/lib-dynload")

resource_bundles = list(app.glob("*.bundle"))
python_modules = [bundle / "PythonModules/ios-device" for bundle in resource_bundles]
python_modules = [path for path in python_modules if path.is_dir()]
if len(python_modules) != 1:
    raise RuntimeError(f"expected one device PythonModules directory, found: {python_modules}")
modules = python_modules[0]
package_count = process_tree(modules, modules.relative_to(app).as_posix())
if package_count != 2:
    raise RuntimeError(f"expected exactly the PyCA and CFFI native extensions, found {package_count}")

for required in (
    modules / "cryptography/__init__.py",
    modules / "cffi/__init__.py",
    modules / "pycparser/__init__.py",
    app / "Frameworks/Python.framework/Python",
    app / "Frameworks/.cryptography.hazmat.bindings._rust.framework/.cryptography.hazmat.bindings._rust",
    app / "Frameworks/._cffi_backend.framework/._cffi_backend",
):
    if not required.is_file():
        raise FileNotFoundError(f"required full-build component missing: {required}")

print(f"converted {stdlib_count} CPython stdlib extensions and {package_count} package extensions")
print("note: frameworks are unsigned; this Linux host has no Apple codesign/actool tools")
```

Invocation:

```sh
python3 /tmp/opencode/finalize-starling-ios-ipa.py \
  "$APP" "$PWD/iosApp/Vendor/Python.xcframework/build/iOS-dylib-Info-template.plist"
```

It reported 68 CPython standard-library extensions and 2 package extensions converted. Before zipping, all 70 `.fwork` pointers and `.origin` files were checked against existing files, and all 70 framework binaries were checked as Mach-O arm64. The temporary finalizer initially omitted a dot in `.origin` paths; the entries were corrected and revalidated before the IPA was created.

The IPA was written without replacing the earlier best-effort artifact:

```sh
OUT="$PWD/xtool/Starling-full-device.ipa"
(cd /tmp/opencode/starling-full-ipa-stage && zip -q -r -X "$OUT" Payload)
unzip -t "$OUT"
sha256sum "$OUT"
```

`unzip -t` reported no compressed-data errors. Final SHA-256 is the value at the top of this record.

## Functional checks and limits

A separate host-only CPython 3.13.16 smoke test installed host wheels into `/tmp/opencode/starling-pyca-host-runtime` and ran against the app's pure-Python module tree:

```sh
"/tmp/opencode/starling-pyca-host-build/bin/pip" install \
  --disable-pip-version-check --no-input --target /tmp/opencode/starling-pyca-host-runtime \
  'cryptography==50.0.2' 'cffi==2.1.1' 'lxmf==1.2.0' 'rns==1.5.7' 'pyserial==3.5'
APP_MODULES=/tmp/opencode/starling-full-ipa-stage/Payload/Starling.app/Starling_Starling.bundle/PythonModules/ios-device
PYTHONPATH="/tmp/opencode/starling-pyca-host-runtime:$APP_MODULES" \
  /tmp/opencode/starling-pyca-host-build/bin/python - <<'PY'
import cffi, LXMF, RNS, serial, starling_ios_runtime
from RNS.Cryptography import Provider
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
assert cffi.__version__ == '2.1.1'
assert serial.VERSION == '3.5'
assert Provider.PROVIDER == Provider.PROVIDER_PYCA, Provider.backend()
alice, bob = x25519.X25519PrivateKey.generate(), x25519.X25519PrivateKey.generate()
assert alice.exchange(bob.public_key()) == bob.exchange(alice.public_key())
key = ed25519.Ed25519PrivateKey.generate()
signature = key.sign(b'Starling iOS PyCA smoke test')
key.public_key().verify(signature, b'Starling iOS PyCA smoke test')
cipher = Cipher(algorithms.AES(b'\x01'*32), modes.CBC(b'\x02'*16))
encryptor = cipher.encryptor()
ciphertext = encryptor.update(b'\x10'*16) + encryptor.finalize()
decryptor = cipher.decryptor()
assert decryptor.update(ciphertext) + decryptor.finalize() == b'\x10'*16
assert starling_ios_runtime.invoke('STATUS') == 'STATUS\tSTOPPED'
print('Host-runtime RNS/PyCA smoke passed')
PY
```

It passed provider selection, X25519, Ed25519, AES-CBC, and bridge status. This was **not** an import or execution test of the iOS Mach-O files.

The IPA had no `_CodeSignature`, `embedded.mobileprovision`, or `Assets.car`. `xcrun --find actool` and `xcrun --find codesign` returned no tool path, and `/usr/libexec/PlistBuddy` was absent. Thus the resulting IPA is a complete unsigned device payload, not a signed/installable or release-ready IPA. No iPhone runtime test was performed. The simulator PyCA wheel was not built because the requested deliverable was the arm64 device IPA.

The detailed attempt-by-attempt transcript, including failed host-tool probes, is `/tmp/opencode/starling-full-ipa-build.md` in the build environment.
