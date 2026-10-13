"""Explicit management of Starling's standard 64-byte Reticulum private identity."""

import base64
import os
import sys
import tempfile
import threading

_lock = threading.RLock()
KEY_BYTES = 64


def identity_path(config_dir):
    return os.path.join(config_dir, "starling_identity")


def load_identity(config_dir):
    """Load only. Missing or corrupt keys must never silently create a new identity."""
    path = identity_path(config_dir)
    if not os.path.isfile(path):
        raise RuntimeError("No identity is configured. Create or import one on the Identity page.")
    with open(path, "rb") as source:
        data = source.read(KEY_BYTES + 1)
    return _from_bytes(data)


def _from_bytes(data):
    if len(data) != KEY_BYTES:
        raise ValueError("Choose a standard 64-byte Reticulum private identity file.")
    import RNS

    identity = RNS.Identity.from_bytes(data)
    if identity is None:
        raise ValueError("The Reticulum identity file is invalid.")
    return identity


def _save(config_dir, identity):
    os.makedirs(config_dir, mode=0o700, exist_ok=True)
    descriptor, temporary_path = tempfile.mkstemp(prefix=".starling-identity-", dir=config_dir)
    try:
        with os.fdopen(descriptor, "wb") as destination:
            destination.write(identity.get_private_key())
            destination.flush()
            os.fsync(destination.fileno())
        os.replace(temporary_path, identity_path(config_dir))
    finally:
        if os.path.exists(temporary_path):
            os.unlink(temporary_path)


def _info(identity):
    import RNS

    address = RNS.Destination.hash_from_name_and_identity("lxmf.delivery", identity).hex()
    return "MANAGED_IDENTITY\t{}\t{}".format(identity.hash.hex(), address)


def invoke(config_dir, command):
    """Management responses contain private material only for an explicit export."""
    fields = command.split("\t")
    action = fields[0]
    try:
        with _lock:
            if action == "IDENTITY_STATUS" and len(fields) == 1:
                if not os.path.exists(identity_path(config_dir)):
                    return "IDENTITY_MISSING"
                return _info(load_identity(config_dir))
            if action == "IDENTITY_CREATE" and len(fields) == 1:
                if os.path.exists(identity_path(config_dir)):
                    raise ValueError("An identity already exists. Export or delete it before creating another.")
                import RNS

                identity = RNS.Identity()
                _save(config_dir, identity)
                return _info(identity)
            if action == "IDENTITY_IMPORT" and len(fields) == 2:
                identity = _from_bytes(base64.b64decode(fields[1], validate=True))
                _save(config_dir, identity)
                return _info(identity)
            if action == "IDENTITY_EXPORT" and len(fields) == 1:
                identity = load_identity(config_dir)
                return "IDENTITY_EXPORT\t" + base64.b64encode(identity.get_private_key()).decode("ascii")
            if action == "IDENTITY_DELETE" and len(fields) == 1:
                try:
                    os.unlink(identity_path(config_dir))
                except FileNotFoundError:
                    pass
                return "IDENTITY_MISSING"
            raise ValueError("Invalid identity management request.")
    except (ValueError, RuntimeError) as error:
        return "ERROR\t" + base64.b64encode(str(error).encode("utf-8")).decode("ascii")
    except Exception:
        # Never include a request or private key in diagnostics.
        return "ERROR\t" + base64.b64encode(b"Could not access the identity file.").decode("ascii")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(2)
    request = sys.stdin.readline(1024).rstrip("\r\n")
    print(invoke(sys.argv[1], request), flush=True)
