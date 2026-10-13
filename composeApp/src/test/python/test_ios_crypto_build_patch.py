"""Regression tests for the PyCA iOS cross-build compatibility patch."""

import importlib.util
from pathlib import Path
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
