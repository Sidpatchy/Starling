"""Build Starling's Android-only RNS wheel until upstream fixes issue #8."""

import base64
import csv
import hashlib
import io
from pathlib import Path
import sys
from urllib.request import urlopen
from zipfile import ZipFile


WHEEL_NAME = "rns-1.5.7-py3-none-any.whl"
WHEEL_URL = (
    "https://files.pythonhosted.org/packages/6a/5a/"
    "ee933b9f465040039286ffa76c5e509f334b61014061cdc1b0e46bcb3753/" + WHEEL_NAME
)
WHEEL_SHA256 = "0e4aaf8cc47f770da34572d2215b45fea900e104b009551600eb9741a41faddf"
ANDROID_ADAPTER = "RNS/Interfaces/Android/RNodeInterface.py"
RECORD = "rns-1.5.7.dist-info/RECORD"
OLD_CHECK = b"if (self.bandwidth < 7800 or self.bandwidth > 500000):"
NEW_CHECK = b"if (self.bandwidth < 7800 or self.bandwidth > 1625000):"


def verify_upstream(wheel):
    if hashlib.sha256(wheel).hexdigest() != WHEEL_SHA256:
        raise ValueError(f"SHA-256 mismatch for upstream {WHEEL_NAME}")


def patch_wheel(wheel):
    output = io.BytesIO()
    with ZipFile(io.BytesIO(wheel)) as upstream:
        if upstream.namelist().count(ANDROID_ADAPTER) != 1:
            raise ValueError(f"Expected exactly one {ANDROID_ADAPTER} in {WHEEL_NAME}")
        adapter = upstream.read(ANDROID_ADAPTER)
        if adapter.count(OLD_CHECK) != 1:
            raise ValueError("Expected exactly one 500 kHz check in the Android RNode adapter")
        adapter = adapter.replace(OLD_CHECK, NEW_CHECK, 1)

        record = list(csv.reader(io.StringIO(upstream.read(RECORD).decode("utf-8"))))
        entries = [row for row in record if row[0] == ANDROID_ADAPTER]
        if len(entries) != 1:
            raise ValueError(f"Expected exactly one {ANDROID_ADAPTER} entry in wheel RECORD")
        digest = base64.urlsafe_b64encode(hashlib.sha256(adapter).digest()).rstrip(b"=").decode("ascii")
        entries[0][1:] = [f"sha256={digest}", str(len(adapter))]
        updated_record = io.StringIO()
        csv.writer(updated_record, lineterminator="\n").writerows(record)
        overrides = {ANDROID_ADAPTER: adapter, RECORD: updated_record.getvalue().encode("utf-8")}

        with ZipFile(output, "w") as patched:
            for entry in upstream.infolist():
                data = overrides[entry.filename] if entry.filename in overrides else upstream.read(entry)
                patched.writestr(entry, data)
    return output.getvalue()


def main(output):
    if output.name != WHEEL_NAME:
        raise ValueError(f"Expected output filename {WHEEL_NAME}")
    with urlopen(WHEEL_URL, timeout=30) as response:
        wheel = response.read()
    verify_upstream(wheel)
    patched = patch_wheel(wheel)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".whl.tmp")
    temporary.write_bytes(patched)
    temporary.replace(output)


if __name__ == "__main__":
    main(Path(sys.argv[1]))
