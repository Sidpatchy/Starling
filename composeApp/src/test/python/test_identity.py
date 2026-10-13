import base64
import hashlib
import os
import stat
import tempfile
import unittest
from unittest.mock import Mock, patch

import starling_identity
import starling_lxmf
import starling_rns
import starling_rrc


class FakeIdentity:
    generated = 0

    def __init__(self, create_keys=True, key=None):
        if create_keys:
            FakeIdentity.generated += 1
        self.key = key if key is not None else bytes(range(64))
        self.hash = hashlib.sha256(self.key).digest()[:16]

    def get_private_key(self):
        return self.key

    @staticmethod
    def from_bytes(data):
        return FakeIdentity(create_keys=False, key=data) if len(data) == 64 else None


class IdentityTest(unittest.TestCase):
    def setUp(self):
        FakeIdentity.generated = 0
        self.directory = tempfile.TemporaryDirectory(prefix="starling-identity-test-")
        self.addCleanup(self.directory.cleanup)
        self.config_dir = os.path.join(self.directory.name, "reticulum")
        self.rns = Mock()
        self.rns.Identity = FakeIdentity
        self.rns.Destination.hash_from_name_and_identity.side_effect = lambda name, identity: hashlib.sha256(name.encode() + identity.hash).digest()[:16]
        self.modules = patch.dict("sys.modules", {"RNS": self.rns, "LXMF": Mock()})
        self.modules.start()
        self.addCleanup(self.modules.stop)

    def invoke(self, command):
        return starling_identity.invoke(self.config_dir, command)

    def test_missing_status_and_runtime_start_never_create_an_identity(self):
        self.assertEqual(self.invoke("IDENTITY_STATUS"), "IDENTITY_MISSING")
        self.assertFalse(os.path.exists(self.config_dir))
        with self.assertRaisesRegex(RuntimeError, "Create or import"):
            starling_identity.load_identity(self.config_dir)
        with self.assertRaisesRegex(RuntimeError, "Create or import"):
            starling_rns.start_node(self.config_dir, "embedded", [{"host": "localhost", "port": 4242}])
        self.rns.Reticulum.assert_not_called()
        self.assertEqual(FakeIdentity.generated, 0)

    def test_explicit_create_export_delete_and_import_preserve_the_identity(self):
        info = self.invoke("IDENTITY_CREATE")
        self.assertTrue(info.startswith("MANAGED_IDENTITY\t"))
        self.assertEqual(FakeIdentity.generated, 1)
        key_path = starling_identity.identity_path(self.config_dir)
        self.assertEqual(stat.S_IMODE(os.stat(key_path).st_mode), 0o600)
        backup = self.invoke("IDENTITY_EXPORT")
        self.assertEqual(len(base64.b64decode(backup.split("\t")[1])), 64)
        self.assertEqual(self.invoke("IDENTITY_DELETE"), "IDENTITY_MISSING")
        self.assertFalse(os.path.exists(key_path))
        self.assertEqual(self.invoke("IDENTITY_IMPORT\t" + backup.split("\t")[1]), info)
        self.assertEqual(self.invoke("IDENTITY_STATUS"), info)
        self.assertEqual(FakeIdentity.generated, 1)

    def test_existing_identity_is_loaded_not_regenerated_or_overwritten_by_create(self):
        info = self.invoke("IDENTITY_CREATE")
        self.assertEqual(self.invoke("IDENTITY_STATUS"), info)
        self.assertTrue(self.invoke("IDENTITY_CREATE").startswith("ERROR\t"))
        self.assertEqual(self.invoke("IDENTITY_STATUS"), info)
        self.assertEqual(FakeIdentity.generated, 1)

    def test_invalid_imports_do_not_replace_the_existing_key(self):
        info = self.invoke("IDENTITY_CREATE")
        backup = self.invoke("IDENTITY_EXPORT")
        for data in [b"", b"short", bytes(65)]:
            response = self.invoke("IDENTITY_IMPORT\t" + base64.b64encode(data).decode())
            self.assertTrue(response.startswith("ERROR\t"))
            self.assertEqual(self.invoke("IDENTITY_STATUS"), info)
            self.assertEqual(self.invoke("IDENTITY_EXPORT"), backup)
        self.assertTrue(self.invoke("IDENTITY_IMPORT\tnot base64!").startswith("ERROR\t"))

    def test_corrupt_saved_identity_never_falls_back_to_generation(self):
        os.makedirs(self.config_dir)
        with open(starling_identity.identity_path(self.config_dir), "wb") as key:
            key.write(b"corrupt")
        self.assertTrue(self.invoke("IDENTITY_STATUS").startswith("ERROR\t"))
        self.assertTrue(self.invoke("IDENTITY_EXPORT").startswith("ERROR\t"))
        self.assertTrue(self.invoke("IDENTITY_CREATE").startswith("ERROR\t"))
        self.assertEqual(FakeIdentity.generated, 0)

    def test_failed_atomic_import_keeps_the_old_key_and_cleans_temporary_files(self):
        self.invoke("IDENTITY_CREATE")
        backup = self.invoke("IDENTITY_EXPORT")
        with patch("starling_identity.os.replace", side_effect=OSError("write failure")):
            response = self.invoke("IDENTITY_IMPORT\t" + base64.b64encode(bytes(reversed(range(64)))).decode())
        self.assertTrue(response.startswith("ERROR\t"))
        self.assertEqual(self.invoke("IDENTITY_EXPORT"), backup)
        self.assertEqual(os.listdir(self.config_dir), ["starling_identity"])

    def test_rrc_and_lxmf_cannot_generate_missing_chat_identities(self):
        self.rns.Reticulum.configdir = self.config_dir
        self.rns.Reticulum.TRUNCATED_HASHLENGTH = 128
        with patch.multiple(starling_rrc, _link=None, _identity=None):
            with self.assertRaisesRegex(RuntimeError, "Create or import"):
                starling_rrc.connect("0123456789abcdef0123456789abcdef", "Operator")
        with self.assertRaisesRegex(RuntimeError, "Create or import"):
            starling_lxmf._identity_for_config(self.config_dir)
        self.assertEqual(FakeIdentity.generated, 0)
        self.assertFalse(os.path.exists(starling_identity.identity_path(self.config_dir)))

    def test_lxmf_nickname_updates_the_announced_delivery_destination(self):
        destination, router = Mock(), Mock()
        destination.hash = b"address"
        with patch.multiple(starling_lxmf, _delivery_destination=destination, _router=router, _display_name="Starling"):
            self.assertEqual(starling_lxmf.set_display_name("  Field operator  "), "Field operator")
            self.assertEqual(destination.display_name, "Field operator")
            router.announce.assert_called_once_with(destination.hash)


if __name__ == "__main__":
    unittest.main()
