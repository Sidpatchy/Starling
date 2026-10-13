"""Optional interoperability checks against the installed reference RNS/LXMF packages."""

import base64
import os
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import starling_identity
import starling_lxmf

try:
    import RNS
    import LXMF
    REFERENCE_AVAILABLE = True
except ImportError:
    REFERENCE_AVAILABLE = False


@unittest.skipUnless(REFERENCE_AVAILABLE, "Reference RNS/LXMF packages are not installed")
class ReferenceIdentityTest(unittest.TestCase):
    def test_standalone_desktop_identity_protocol_returns_one_line_without_starting_a_node(self):
        with tempfile.TemporaryDirectory(prefix="starling-reference-cli-") as directory:
            for command, prefix in [
                ("IDENTITY_STATUS", "IDENTITY_MISSING"),
                ("IDENTITY_CREATE", "MANAGED_IDENTITY\t"),
                ("IDENTITY_EXPORT", "IDENTITY_EXPORT\t"),
                ("IDENTITY_DELETE", "IDENTITY_MISSING"),
            ]:
                result = subprocess.run(
                    [sys.executable, starling_identity.__file__, directory], input=command + "\n",
                    text=True, capture_output=True, timeout=10,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(result.stdout.splitlines()), 1)
                self.assertTrue(result.stdout.startswith(prefix))
            self.assertFalse(os.path.exists(os.path.join(directory, "config")))

    def test_export_is_a_standard_reticulum_private_identity_and_round_trips(self):
        with tempfile.TemporaryDirectory(prefix="starling-reference-identity-") as directory:
            original = starling_identity.invoke(directory, "IDENTITY_CREATE")
            exported = starling_identity.invoke(directory, "IDENTITY_EXPORT").split("\t")[1]
            data = base64.b64decode(exported)
            reference = RNS.Identity.from_bytes(data)
            self.assertEqual(len(data), 64)
            self.assertIsNotNone(reference)
            self.assertEqual(original.split("\t")[1], reference.hash.hex())
            expected_address = RNS.Destination.hash_from_name_and_identity("lxmf.delivery", reference).hex()
            self.assertEqual(original.split("\t")[2], expected_address)
            self.assertEqual(starling_identity.invoke(directory, "IDENTITY_DELETE"), "IDENTITY_MISSING")
            self.assertEqual(starling_identity.invoke(directory, "IDENTITY_IMPORT\t" + exported), original)
            saved = RNS.Identity.from_file(starling_identity.identity_path(directory))
            self.assertEqual(saved.hash, reference.hash)
            self.assertEqual(saved.get_private_key(), data)

    def test_missing_identity_is_not_generated_by_reading_it(self):
        with tempfile.TemporaryDirectory(prefix="starling-reference-missing-") as directory:
            self.assertEqual(starling_identity.invoke(directory, "IDENTITY_STATUS"), "IDENTITY_MISSING")
            self.assertFalse(os.path.exists(starling_identity.identity_path(directory)))
            with self.assertRaises(RuntimeError):
                starling_identity.load_identity(directory)

    def test_global_lxmf_nickname_is_encoded_in_reference_announce_app_data(self):
        router = object.__new__(LXMF.LXMRouter)
        destination = SimpleNamespace(hash=b"destination", display_name="Starling", stamp_cost=None)
        router.delivery_destinations = {destination.hash: destination}
        announced = []
        router.announce = lambda address: announced.append(router.get_announce_app_data(address))
        with patch.multiple(starling_lxmf, _router=router, _delivery_destination=destination, _display_name="Starling"):
            starling_lxmf.set_display_name("  Field 🌍  ")
        self.assertEqual(len(announced), 1)
        self.assertEqual(RNS.vendor.umsgpack.unpackb(announced[0])[0], "Field 🌍".encode())


if __name__ == "__main__":
    unittest.main()
