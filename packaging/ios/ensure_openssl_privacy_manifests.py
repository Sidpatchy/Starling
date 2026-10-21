#!/usr/bin/env python3
"""Install or validate OpenSSL privacy manifests in embedded CPython frameworks."""

from __future__ import annotations

import argparse
import plistlib
import shutil
from pathlib import Path


MANIFEST_SOURCE = Path(__file__).resolve().with_name("OpenSSL.xcprivacy")
MODULES = ("_hashlib", "_ssl")


def fail(message: str) -> None:
    raise SystemExit(f"Error: {message}")


def ensure_manifests(app: Path, *, check_only: bool = False) -> None:
    try:
        expected = plistlib.loads(MANIFEST_SOURCE.read_bytes())
    except (OSError, plistlib.InvalidFileException) as error:
        fail(f"cannot read OpenSSL privacy manifest {MANIFEST_SOURCE}: {error}")

    for module in MODULES:
        framework = app / "Frameworks" / f"{module}.framework"
        destination = framework / "PrivacyInfo.xcprivacy"
        if not framework.is_dir():
            fail(f"expected embedded Python framework is missing: {framework}")

        if not check_only:
            shutil.copyfile(MANIFEST_SOURCE, destination)
        elif not destination.is_file():
            fail(f"OpenSSL privacy manifest is missing: {destination}")

        try:
            actual = plistlib.loads(destination.read_bytes())
        except (OSError, plistlib.InvalidFileException) as error:
            fail(f"invalid OpenSSL privacy manifest {destination}: {error}")
        if actual != expected:
            fail(f"OpenSSL privacy manifest does not match the expected manifest: {destination}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate without modifying the app")
    parser.add_argument("app", type=Path, help="path to the Starling .app bundle")
    arguments = parser.parse_args()
    ensure_manifests(arguments.app, check_only=arguments.check)


if __name__ == "__main__":
    main()
