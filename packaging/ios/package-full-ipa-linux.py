#!/usr/bin/env python3
"""Add device PyCA wheels to an unsigned Starling IPA and package its payload."""

from __future__ import annotations

import hashlib
import os
import plistlib
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile

from ensure_openssl_privacy_manifests import ensure_manifests


def fail(message: str) -> None:
    raise SystemExit(f"Error: {message}")


def safe_extract(archive: ZipFile, root: Path) -> None:
    root = root.resolve()
    for item in archive.infolist():
        target = (root / item.filename).resolve()
        if target != root and root not in target.parents:
            fail(f"archive member escapes extraction directory: {item.filename}")
        mode = item.external_attr >> 16
        if item.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        data = archive.read(item)
        if stat.S_ISLNK(mode):
            link = data.decode("utf-8")
            link_target = (target.parent / link).resolve()
            if link_target != root and root not in link_target.parents:
                fail(f"archive symlink escapes extraction directory: {item.filename} -> {link}")
            if target.exists() or target.is_symlink():
                target.unlink()
            target.symlink_to(link)
        else:
            target.write_bytes(data)
            permissions = mode & 0o777
            if permissions:
                target.chmod(permissions)


def install_wheel(wheel_path: Path, target: Path) -> None:
    with ZipFile(wheel_path) as wheel:
        safe_extract(wheel, target)
    print(f"Installed wheel payload: {wheel_path.name}")


def has_arm64_macho(path: Path) -> bool:
    with path.open("rb") as binary:
        header = binary.read(4096)
    if len(header) < 8:
        return False
    if header[:4] == b"\xcf\xfa\xed\xfe":  # MH_MAGIC_64, little endian
        return struct.unpack_from("<I", header, 4)[0] == 0x0100000C
    if header[:4] == b"\xfe\xed\xfa\xcf":  # MH_CIGAM_64, big endian
        return struct.unpack_from(">I", header, 4)[0] == 0x0100000C
    if header[:4] in (b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca"):
        byte_order = ">" if header[:4] == b"\xca\xfe\xba\xbe" else "<"
        count = struct.unpack_from(f"{byte_order}I", header, 4)[0]
        return any(
            struct.unpack_from(f"{byte_order}I", header, 8 + index * 20)[0] == 0x0100000C
            for index in range(count)
            if len(header) >= 8 + (index + 1) * 20
        )
    return False


def convert_extensions(
    app: Path,
    tree: Path,
    template: dict,
    bundle_id: str,
    package_modules: bool = False,
) -> int:
    converted = 0
    frameworks = app / "Frameworks"
    frameworks.mkdir(parents=True, exist_ok=True)
    for extension in sorted(tree.rglob("*.so")):
        relative_extension = extension.relative_to(app).as_posix()
        module_path = extension.relative_to(tree)
        module_name = module_path.as_posix().split(".", 1)[0].replace("/", ".")
        if package_modules:
            module_name = f".{module_name}"
        framework_folder = f"Frameworks/{module_name}.framework"
        framework_path = app / framework_folder
        if framework_path.exists():
            fail(f"refusing to overwrite existing extension framework: {framework_path}")

        framework_path.mkdir(parents=True)
        info = dict(template)
        info["CFBundleExecutable"] = module_name
        info["CFBundleIdentifier"] = f"{bundle_id}.{module_name.replace('_', '-')}"
        with (framework_path / "Info.plist").open("wb") as plist:
            plistlib.dump(info, plist, fmt=plistlib.FMT_XML, sort_keys=False)

        binary = framework_path / module_name
        shutil.move(str(extension), binary)
        binary.chmod(binary.stat().st_mode | 0o111)
        placeholder = extension.with_name(extension.name[:-3] + ".fwork")
        placeholder.write_text(f"{framework_folder}/{module_name}\n", encoding="utf-8")
        (framework_path / f"{module_name}.origin").write_text(
            f"{relative_extension[:-3]}.fwork\n", encoding="utf-8"
        )
        converted += 1
    return converted


def validate_app(app: Path, modules: Path, framework_count: int) -> None:
    required = (
        app / "Frameworks/Python.framework/Python",
        app / "Frameworks/.cryptography.hazmat.bindings._rust.framework/.cryptography.hazmat.bindings._rust",
        app / "Frameworks/._cffi_backend.framework/._cffi_backend",
        modules / "cryptography/__init__.py",
        modules / "cffi/__init__.py",
        modules / "pycparser/__init__.py",
    )
    for path in required:
        if not path.is_file():
            fail(f"required full-build component is missing: {path}")

    ensure_manifests(app, check_only=True)

    app_info = plistlib.loads((app / "Info.plist").read_bytes())
    executable = app / app_info["CFBundleExecutable"]
    native_binaries = [
        app / "Frameworks/Python.framework/Python",
        executable,
        *(
            path
            for path in (app / "Frameworks").glob("*.framework/*")
            if path.is_file() and path.name != "Info.plist" and not path.name.endswith(".origin")
        ),
    ]
    for binary in native_binaries:
        if not has_arm64_macho(binary):
            fail(f"expected an arm64 Mach-O binary: {binary}")

    placeholders = list(app.rglob("*.fwork"))
    origins = list((app / "Frameworks").glob("*.framework/*.origin"))
    if framework_count != len(placeholders) or framework_count != len(origins):
        fail(
            f"extension mapping count mismatch: converted={framework_count}, "
            f"placeholders={len(placeholders)}, origins={len(origins)}"
        )
    for placeholder in placeholders:
        framework_binary = (app / placeholder.read_text(encoding="utf-8").strip()).resolve()
        if not framework_binary.is_file() or not has_arm64_macho(framework_binary):
            fail(f"invalid .fwork mapping: {placeholder}")
    for origin in origins:
        source_placeholder = (app / origin.read_text(encoding="utf-8").strip()).resolve()
        if not source_placeholder.is_file():
            fail(f"invalid .origin mapping: {origin}")


def main() -> None:
    if len(sys.argv) != 6:
        fail(
            "usage: package-full-ipa-linux.py <base.ipa> <cryptography.whl> "
            "<cffi.whl> <pycparser.whl> <output.ipa>"
        )
    base_ipa, crypto_wheel, cffi_wheel, pycparser_wheel, output_ipa = map(
        lambda value: Path(value).expanduser().resolve(), sys.argv[1:]
    )
    for path in (base_ipa, crypto_wheel, cffi_wheel, pycparser_wheel):
        if not path.is_file():
            fail(f"input file does not exist: {path}")
    if output_ipa == base_ipa:
        fail("output IPA must not overwrite the input base IPA")
    for executable in ("unzip", "zip"):
        if shutil.which(executable) is None:
            fail(f"{executable} is required to assemble the IPA")

    output_ipa.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="starling-full-ipa-") as temporary:
        temporary_path = Path(temporary)
        stage = temporary_path / "stage"
        stage.mkdir()
        with ZipFile(base_ipa) as archive:
            safe_extract(archive, stage)

        payload = stage / "Payload"
        apps = sorted(payload.glob("*.app"))
        if len(apps) != 1:
            fail(f"expected exactly one Payload/*.app, found {len(apps)}")
        app = apps[0]
        modules_candidates = list(app.glob("*.bundle/PythonModules/ios-device"))
        if len(modules_candidates) != 1:
            fail(f"expected one device PythonModules directory, found {modules_candidates}")
        modules = modules_candidates[0]
        python_stdlib = app / "python/lib/python3.13/lib-dynload"
        if not python_stdlib.is_dir():
            fail(f"embedded CPython 3.13 extension directory is missing: {python_stdlib}")
        template_path = Path(
            __file__
        ).resolve().parent.parent.parent / "iosApp/Vendor/Python.xcframework/build/iOS-dylib-Info-template.plist"
        if not template_path.is_file():
            fail(f"Python framework metadata template is missing: {template_path}")
        app_info = plistlib.loads((app / "Info.plist").read_bytes())
        template = plistlib.loads(template_path.read_bytes())

        for wheel in (crypto_wheel, cffi_wheel, pycparser_wheel):
            install_wheel(wheel, modules)
        for cache in list(modules.rglob("__pycache__")):
            shutil.rmtree(cache)
        for bytecode in modules.rglob("*.py[co]"):
            bytecode.unlink()

        stdlib_count = convert_extensions(
            app, python_stdlib, template, app_info["CFBundleIdentifier"]
        )
        package_count = convert_extensions(
            app, modules, template, app_info["CFBundleIdentifier"], package_modules=True
        )
        if stdlib_count == 0 or package_count != 2:
            fail(
                f"expected CPython extensions and exactly two PyCA native modules; "
                f"found stdlib={stdlib_count}, packages={package_count}"
            )
        framework_count = stdlib_count + package_count
        ensure_manifests(app)
        validate_app(app, modules, framework_count)

        staged_ipa = temporary_path / "Starling-full-device.ipa"
        subprocess.run(
            ["zip", "-q", "-r", "-X", "-y", str(staged_ipa), "Payload"],
            cwd=stage,
            check=True,
        )
        with ZipFile(staged_ipa) as archive:
            bad_member = archive.testzip()
            if bad_member is not None:
                fail(f"IPA CRC check failed at {bad_member}")
        os.replace(staged_ipa, output_ipa)

    digest = hashlib.sha256(output_ipa.read_bytes()).hexdigest()
    print(f"Converted {stdlib_count} CPython and {package_count} PyCA native extensions.")
    print(f"Unsigned arm64 device IPA: {output_ipa}")
    print(f"SHA-256: {digest}")
    print("Note: signing, asset-catalog compilation, and on-device testing require macOS/Xcode.")


if __name__ == "__main__":
    main()
