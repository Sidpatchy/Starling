#!/usr/bin/env python3
"""Make the pinned PyCA sdist self-contained for iOS cross-compilation."""

from pathlib import Path
import sys


def patch_cffi_build_script(path: Path) -> None:
    source = path.read_text(encoding="utf-8")
    original = '    let output = Command::new(&python)\n        .env("OUT_DIR", &out_dir)\n'
    replacement = (
        '    let output = Command::new(&python)\n'
        '        .env("PYTHONPATH", env::var("STARLING_CFFI_HOST_PYTHONPATH")\n'
        '            .expect("STARLING_CFFI_HOST_PYTHONPATH must be set for iOS builds"))\n'
        '        .env("OUT_DIR", &out_dir)\n'
    )
    if source.count(replacement) == 1:
        return
    if source.count(original) != 1:
        raise ValueError("Unexpected PyCA build.rs; refusing to patch the CFFI cross-build.")
    path.write_text(source.replace(original, replacement), encoding="utf-8")


def enable_vendored_openssl(path: Path) -> None:
    source = path.read_text(encoding="utf-8")
    original = "openssl.workspace = true"
    replacement = 'openssl = { workspace = true, features = ["vendored"] }'
    if source.count(replacement) == 1:
        return
    if source.count(original) != 1:
        raise ValueError("Unexpected PyCA Cargo.toml; refusing to enable the vendored OpenSSL feature.")
    path.write_text(source.replace(original, replacement), encoding="utf-8")


def patch(source_root: Path) -> None:
    patch_cffi_build_script(source_root / "src/rust/cryptography-cffi/build.rs")
    enable_vendored_openssl(source_root / "src/rust/Cargo.toml")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch-cryptography-ios-build.py <cryptography-source-directory>")
    patch(Path(sys.argv[1]))
