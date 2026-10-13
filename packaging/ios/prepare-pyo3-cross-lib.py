#!/usr/bin/env python3
"""Stage the active cibuildwheel Python slice for PyO3's iOS linker config."""

from pathlib import Path
import shutil
import sys
import tempfile


def default_stage_root(multiarch: str) -> Path:
    return Path(tempfile.gettempdir()) / "starling-pyo3-cross" / multiarch


def stage_python_library(
    slice_root: Path, multiarch: str, version: str = "3.13", stage_root: Path | None = None
) -> Path:
    # Stage outside the XCframework slice: the cibuildwheel iOS testbed bundles
    # the slice into the app, and iOS rejects symlinks that escape the bundle.
    if stage_root is None:
        stage_root = default_stage_root(multiarch)
    config_dir = slice_root / "platform-config" / multiarch
    sysconfig_files = list(config_dir.glob("_sysconfigdata*.py"))
    if len(sysconfig_files) != 1:
        raise RuntimeError(f"Expected one {multiarch} Python sysconfig file in {config_dir}")

    # BeeWare's XCFramework releases have used both a loose libpython dylib
    # and a Python.framework binary. PyO3 links using `-lpythonX.Y`, so expose
    # either real target binary with the conventional libpython name.
    python_library = slice_root / "lib" / f"libpython{version}.dylib"
    if not python_library.is_file():
        python_library = slice_root / "Python.framework" / "Python"
    if not python_library.is_file():
        raise RuntimeError(f"Target Python library/framework binary is missing from {slice_root}")

    cross_lib_dir = stage_root / "lib" / "pyo3-cross"
    cross_lib_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(sysconfig_files[0], cross_lib_dir / sysconfig_files[0].name)

    staged_library = cross_lib_dir / f"libpython{version}.dylib"
    if staged_library.exists() or staged_library.is_symlink():
        staged_library.unlink()
    staged_library.symlink_to(python_library)

    # cryptography-cffi's build.rs derives its header dir from PYO3_CROSS_LIB_DIR
    # as <lib_dir>/../../include/<basename(lib_dir)>, i.e. include/pyo3-cross.
    include_src = slice_root / "include" / f"python{version}"
    if not include_src.is_dir():
        raise RuntimeError(f"Target Python headers are missing: {include_src}")
    include_dst = stage_root / "include" / cross_lib_dir.name
    include_dst.parent.mkdir(parents=True, exist_ok=True)
    if include_dst.is_symlink():
        include_dst.unlink()
    elif include_dst.exists():
        shutil.rmtree(include_dst)
    include_dst.symlink_to(include_src)

    return cross_lib_dir


def active_python_slice() -> tuple[Path, str, str]:
    python = shutil.which(f"python{sys.version_info.major}.{sys.version_info.minor}")
    if python is None:
        raise RuntimeError("Could not locate cibuildwheel's target Python wrapper on PATH")
    target_slice = Path(python).parent.parent
    multiarch = getattr(sys.implementation, "_multiarch", None)
    if not multiarch:
        raise RuntimeError("The active cibuildwheel Python does not report its iOS multiarch")
    return target_slice, multiarch, f"{sys.version_info.major}.{sys.version_info.minor}"


if __name__ == "__main__":
    slice_root, multiarch, python_version = active_python_slice()
    print(stage_python_library(slice_root, multiarch, python_version))
