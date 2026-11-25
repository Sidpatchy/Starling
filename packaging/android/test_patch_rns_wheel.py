import base64
import csv
import hashlib
import io
import unittest
from zipfile import ZipFile

from patch_rns_wheel import ANDROID_ADAPTER, RECORD, patch_wheel, verify_upstream


DESKTOP_ADAPTER = "RNS/Interfaces/RNodeInterface.py"
ANDROID_CHECK = b"if (self.bandwidth < 7800 or self.bandwidth > 500000):"
DESKTOP_CHECK = b"if (self.bandwidth < 7800 or self.bandwidth > 1625000):"


def wheel_with_adapter(source):
    wheel = io.BytesIO()
    with ZipFile(wheel, "w") as archive:
        archive.writestr(ANDROID_ADAPTER, source)
        archive.writestr(DESKTOP_ADAPTER, DESKTOP_CHECK)
        archive.writestr(
            RECORD,
            f"{ANDROID_ADAPTER},sha256=old,{len(source)}\n{DESKTOP_ADAPTER},sha256=unchanged,0\n{RECORD},,\n",
        )
    return wheel.getvalue()


class RnsWheelPatchTest(unittest.TestCase):
    def test_patches_only_android_and_updates_wheel_record(self):
        source = ANDROID_CHECK + b"\n    self.validcfg = False\n"
        with ZipFile(io.BytesIO(patch_wheel(wheel_with_adapter(source)))) as archive:
            self.assertEqual(archive.read(DESKTOP_ADAPTER), DESKTOP_CHECK)
            adapter = archive.read(ANDROID_ADAPTER)
            self.assertEqual(adapter, DESKTOP_CHECK + b"\n    self.validcfg = False\n")
            digest = base64.urlsafe_b64encode(hashlib.sha256(adapter).digest()).decode().rstrip("=")
            rows = list(csv.reader(archive.read(RECORD).decode().splitlines()))
            self.assertIn([ANDROID_ADAPTER, f"sha256={digest}", str(len(adapter))], rows)
            self.assertIn([DESKTOP_ADAPTER, "sha256=unchanged", "0"], rows)

    def test_rejects_unexpected_or_duplicate_checks(self):
        for source in (DESKTOP_CHECK, ANDROID_CHECK + b"\n" + ANDROID_CHECK):
            with self.subTest(source=source), self.assertRaisesRegex(ValueError, "exactly one 500 kHz check"):
                patch_wheel(wheel_with_adapter(source))

    def test_rejects_any_unpinned_download(self):
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            verify_upstream(wheel_with_adapter(ANDROID_CHECK))
