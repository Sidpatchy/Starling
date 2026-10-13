#!/usr/bin/env bash
# PyO3 cross-compilation setup for iOS.
# This script is SOURCED by CIBW_BEFORE_BUILD in the build environment where
# the target Python wrapper is on PATH and sys.implementation._multiarch is set.

set -euo pipefail

# Find the active target Python slice from cibuildwheel's cross-venv.
# The Python wrapper on PATH points to the target slice's bin directory.
python_wrapper="$(command -v "python${PYO3_CROSS_PYTHON_VERSION:-3.13}")"
if [[ -z "$python_wrapper" ]]; then
    echo "ERROR: Could not locate target Python wrapper on PATH" >&2
    exit 1
fi

# The slice root is two levels up from the bin directory.
slice_root="$(cd "$(dirname "$python_wrapper")/.." && pwd)"

# Get the multiarch from the target Python's sys.implementation._multiarch
multiarch="$("$python_wrapper" -c 'import sys; print(getattr(sys.implementation, "_multiarch", ""))' 2>/dev/null)"
if [[ -z "$multiarch" ]]; then
    echo "ERROR: Target Python does not report its iOS multiarch" >&2
    exit 1
fi

version="${PYO3_CROSS_PYTHON_VERSION:-3.13}"

# Stage the Python library and headers for PyO3
config_dir="$slice_root/platform-config/$multiarch"
sysconfig_files=("$config_dir"/_sysconfigdata*.py)
if [[ ${#sysconfig_files[@]} -ne 1 ]]; then
    echo "ERROR: Expected one $multiarch Python sysconfig file in $config_dir" >&2
    exit 1
fi

# BeeWare's XCFramework releases have used both a loose libpython dylib
# and a Python.framework binary. PyO3 links using `-lpythonX.Y`, so expose
# either real target binary with the conventional libpython name.
python_library="$slice_root/lib/libpython$version.dylib"
if [[ ! -f "$python_library" ]]; then
    python_library="$slice_root/Python.framework/Python"
fi
if [[ ! -f "$python_library" ]]; then
    echo "ERROR: Target Python library/framework binary is missing from $slice_root" >&2
    exit 1
fi

# Stage Python headers
include_src="$slice_root/include/python$version"
if [[ ! -d "$include_src" ]]; then
    echo "ERROR: Target Python headers are missing: $include_src" >&2
    exit 1
fi

cross_lib_dir="$slice_root/lib/pyo3-cross"
mkdir -p "$cross_lib_dir/include"

# Copy sysconfigdata
cp -f "${sysconfig_files[0]}" "$cross_lib_dir/"

# Symlink libpython
staged_library="$cross_lib_dir/libpython$version.dylib"
rm -f "$staged_library"
ln -s "$python_library" "$staged_library"

# Symlink include directory
include_dst="$cross_lib_dir/include/python$version"
rm -rf "$include_dst"
ln -s "$include_src" "$include_dst"

# Export for PyO3
export PYO3_CROSS_LIB_DIR="$cross_lib_dir"