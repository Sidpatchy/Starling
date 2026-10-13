"""Regression tests for the PyCA iOS cross-build compatibility patch."""

import importlib.util
from pathlib import Path
import runpy
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[4]
PATCH_PATH = ROOT / "packaging/ios/patch-cryptography-ios-build.py"
SPEC = importlib.util.spec_from_file_location("starling_ios_crypto_patch", PATCH_PATH)
PATCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCHER)


class IosCryptoBuildPatchTest(unittest.TestCase):
    def test_wheel_builder_defines_and_uses_the_repository_root(self):
        script = (ROOT / "packaging/ios/build-ios-cryptography-wheels.sh").read_text(encoding="utf-8")
        self.assertIn('ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"', script)
        self.assertIn('python3 "$ROOT/packaging/ios/patch-cryptography-ios-build.py"', script)

    def test_builder_passes_the_active_cross_python_library_to_pyo3(self):
        script = (ROOT / "packaging/ios/build-ios-cryptography-wheels.sh").read_text(encoding="utf-8")
        self.assertIn("PYO3_CROSS=1", script)
        self.assertIn("PYO3_CROSS_PYTHON_VERSION=3.13", script)
        self.assertIn("PYO3_CROSS_LIB_DIR=\\$(python3 $PYO3_CONFIG_SCRIPT)", script)

    def test_target_slice_staging_keeps_sysconfig_and_library_together(self):
        helper_path = ROOT / "packaging/ios/prepare-pyo3-cross-lib.py"
        with tempfile.TemporaryDirectory(prefix="starling-pyo3-cross-") as temporary:
            slice_root = Path(temporary) / "ios-arm64_x86_64-simulator"
            sysconfig_dir = slice_root / "platform-config/arm64-iphonesimulator"
            library_dir = slice_root / "lib"
            sysconfig_dir.mkdir(parents=True)
            library_dir.mkdir()
            sysconfig_file = sysconfig_dir / "_sysconfigdata__ios_arm64-iphonesimulator.py"
            sysconfig_file.write_text("build_time_vars = {}\n", encoding="utf-8")
            library = library_dir / "libpython3.13.dylib"
            library.write_bytes(b"simulator-python")

            namespace = runpy.run_path(str(helper_path))
            staged = namespace["stage_python_library"](slice_root, "arm64-iphonesimulator")

            self.assertEqual((staged / sysconfig_file.name).read_text(encoding="utf-8"), "build_time_vars = {}\n")
            self.assertTrue((staged / library.name).is_symlink())
            self.assertEqual((staged / library.name).resolve(), library.resolve())

    def make_source_tree(self, root):
        cffi = root / "src/rust/cryptography-cffi"
        cffi.mkdir(parents=True)
        (cffi / "build.rs").write_text(
            '    let output = Command::new(&python)\n        .env("OUT_DIR", &out_dir)\n',
            encoding="utf-8",
        )
        rust = root / "src/rust"
        (rust / "Cargo.toml").write_text("openssl.workspace = true\n", encoding="utf-8")
        return rust

    def test_patches_host_cffi_backend_and_enables_vendored_openssl(self):
        with tempfile.TemporaryDirectory(prefix="starling-ios-crypto-patch-") as temporary:
            root = Path(temporary)
            rust = self.make_source_tree(root)

            PATCHER.patch(root)

            build_script = (rust / "cryptography-cffi/build.rs").read_text(encoding="utf-8")
            manifest = (rust / "Cargo.toml").read_text(encoding="utf-8")
            self.assertIn('STARLING_CFFI_HOST_PYTHONPATH', build_script)
            self.assertIn('openssl = { workspace = true, features = ["vendored"] }', manifest)

            # Re-running the patch is safe, including when a wheel build retries.
            PATCHER.patch(root)
            self.assertEqual(build_script, (rust / "cryptography-cffi/build.rs").read_text(encoding="utf-8"))
            self.assertEqual(manifest, (rust / "Cargo.toml").read_text(encoding="utf-8"))

    def test_unexpected_pinned_sources_fail_closed_without_modification(self):
        with tempfile.TemporaryDirectory(prefix="starling-ios-crypto-patch-") as temporary:
            root = Path(temporary)
            rust = self.make_source_tree(root)
            manifest = rust / "Cargo.toml"
            manifest.write_text("some_future_openssl_layout.workspace = true\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "Unexpected PyCA Cargo.toml"):
                PATCHER.patch(root)

            self.assertEqual("some_future_openssl_layout.workspace = true\n", manifest.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
