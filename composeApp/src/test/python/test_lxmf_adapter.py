"""LXMF adapter regressions that do not require RNS, LXMF, or a network."""

import queue
import sys
import threading
import time
import types
import unittest

import starling_bridge_protocol as protocol
import starling_lxmf as lxmf

DELIVERY_HASH = "d1" * 16
IDENTITY_HASH = "b2" * 16


class _FakeIdentity:
    def __init__(self, hex_hash):
        self.hash = bytes.fromhex(hex_hash)


class _FakeDestination:
    def __init__(self):
        self.hash = bytes.fromhex(DELIVERY_HASH)
        self.display_name = ""


class _FakeLXMessage:
    SOURCE_UNKNOWN = 1
    SIGNATURE_INVALID = 2
    DELIVERED = 3
    OPPORTUNISTIC = 2

    def __init__(self, signature_validated=True, unverified_reason=None,
                 source_hash=IDENTITY_HASH, body="Hello", packed=b"packed-message"):
        self.signature_validated = signature_validated
        self.unverified_reason = unverified_reason
        self.source_hash = bytes.fromhex(source_hash)
        self.hash = bytes.fromhex("c3" * 16)
        self._body = body
        self.timestamp = 1000000.0
        self.state = self.DELIVERED
        self.destination_hash = bytes.fromhex(DELIVERY_HASH)
        self.packed = packed

    def content_as_string(self):
        return self._body

    @classmethod
    def unpack_from_bytes(cls, packed):
        return cls()


class _FakeLXMRouter:
    def __init__(self, identity, storagepath, name, autopeer):
        self.registered_callback = None
        self.announced = None
        self.exit_handler_called = False
        self.fail_register = False
        self.fail_announce = False

    def register_delivery_identity(self, identity, display_name=""):
        if self.fail_register:
            return None
        return _FakeDestination()

    def register_delivery_callback(self, callback):
        self.registered_callback = callback

    def announce(self, destination_hash):
        self.announced = destination_hash
        if self.fail_announce:
            raise RuntimeError("announce failed")

    def exit_handler(self):
        self.exit_handler_called = True


class _FakeRNS:
    class Identity:
        @staticmethod
        def recall(hash_hex):
            return None

        @staticmethod
        def recall_app_data(hash_hex):
            return None

    class Transport:
        @staticmethod
        def request_path(hash_hex):
            pass

    class Destination:
        def __init__(self, *args, **kwargs):
            pass

        @staticmethod
        def hash(identity_hash, *aspects):
            return bytes.fromhex(DELIVERY_HASH)

    class vendor:
        class platformutils:
            @staticmethod
            def is_android():
                return False


class _FakeStarlingRns:
    def construct_without_required_process_signals(self, factory):
        return factory()


def _install_fakes(router=None):
    if router is None:
        router = _FakeLXMRouter(None, None, None, None)
    lx_module = types.ModuleType("LXMF")
    lx_module.LXMRouter = lambda *args, **kwargs: router
    lx_module.LXMessage = _FakeLXMessage
    rns_module = types.ModuleType("RNS")
    rns_module.Identity = _FakeRNS.Identity
    rns_module.Transport = _FakeRNS.Transport
    rns_module.Destination = _FakeRNS.Destination
    rns_module.vendor = _FakeRNS.vendor
    identity_module = types.ModuleType("starling_identity")
    identity_module.load_identity = lambda config_dir: _FakeIdentity(IDENTITY_HASH)
    rns_bridge_module = types.ModuleType("starling_rns")
    rns_bridge_module.construct_without_required_process_signals = (
        _FakeStarlingRns().construct_without_required_process_signals
    )
    return {
        "LXMF": lx_module,
        "RNS": rns_module,
        "starling_identity": identity_module,
        "starling_rns": rns_bridge_module,
    }


class LxmfAdapterTest(unittest.TestCase):
    def setUp(self):
        lxmf._router = None
        lxmf._delivery_destination = None
        lxmf._identity = None
        lxmf._data_dir = None
        lxmf._display_name = "Starling"
        lxmf._shutdown.clear()
        lxmf._events = queue.Queue()
        self._replaced_modules = []

    def tearDown(self):
        for name, previous in self._replaced_modules:
            if previous is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = previous
        try:
            lxmf.stop()
        except Exception:
            pass
        lxmf._router = None
        lxmf._delivery_destination = None
        lxmf._identity = None
        lxmf._data_dir = None
        lxmf._display_name = "Starling"
        lxmf._shutdown.clear()
        lxmf._events = queue.Queue()

    def _with_fakes(self, router=None):
        fakes = _install_fakes(router)
        for name, module in fakes.items():
            self._replaced_modules.append((name, sys.modules.get(name)))
            sys.modules[name] = module

    def test_start_returns_local_address_and_emits_identity_and_address(self):
        router = _FakeLXMRouter(None, None, None, None)
        self._with_fakes(router)
        address = lxmf.start("/private/var/starling", "Starling")

        self.assertEqual(address, DELIVERY_HASH)
        self.assertIs(lxmf._router, router)
        self.assertIsNotNone(lxmf._delivery_destination)
        self.assertEqual(router.announced, lxmf._delivery_destination.hash)
        lines = lxmf.poll_event_lines().splitlines()
        self.assertTrue(any(line.startswith("IDENTITY\t") for line in lines))
        self.assertTrue(any(line.startswith("LOCAL_ADDRESS\t") for line in lines))

    def test_start_failure_after_router_creation_clears_state_and_reports_original_error(self):
        router = _FakeLXMRouter(None, None, None, None)
        router.fail_announce = True
        self._with_fakes(router)
        with self.assertRaisesRegex(RuntimeError, "announce failed"):
            lxmf.start("/private/var/starling", "Starling")

        self.assertIsNone(lxmf._router)
        self.assertIsNone(lxmf._delivery_destination)
        self.assertTrue(router.exit_handler_called)

    def test_start_refused_delivery_identity_leaves_no_router_behind(self):
        router = _FakeLXMRouter(None, None, None, None)
        router.fail_register = True
        self._with_fakes(router)
        with self.assertRaisesRegex(RuntimeError, "delivery identity"):
            lxmf.start("/private/var/starling", "Starling")

        self.assertIsNone(lxmf._router)
        self.assertIsNone(lxmf._delivery_destination)

    def test_start_recovers_from_router_without_delivery_destination(self):
        # Simulate the previous failure mode: a router exists but no delivery
        # identity was registered. A later start must retry instead of
        # short-circuiting to an empty local address.
        lxmf._router = object()
        router = _FakeLXMRouter(None, None, None, None)
        self._with_fakes(router)
        address = lxmf.start("/private/var/starling", "Starling")

        self.assertEqual(address, DELIVERY_HASH)
        self.assertIs(lxmf._router, router)
        self.assertIsNotNone(lxmf._delivery_destination)

    def test_send_direct_before_start_raises(self):
        self._with_fakes()
        with self.assertRaisesRegex(RuntimeError, "Start the Reticulum node before sending"):
            lxmf.send_direct(DELIVERY_HASH, "Hello")

    def test_send_during_shutdown_reports_error_instead_of_silent_drop(self):
        router = _FakeLXMRouter(None, None, None, None)
        self._with_fakes(router)
        lxmf.start("/private/var/starling", "Starling")

        # Block peer path resolution so the send thread is still waiting when
        # the node stops, then release it and confirm an error event is
        # emitted instead of the message being dropped silently.
        resolution_release = threading.Event()
        rns_module = sys.modules["RNS"]
        original_request_path = rns_module.Transport.request_path

        def blocking_request_path(hash_hex):
            resolution_release.wait()

        rns_module.Transport.request_path = blocking_request_path
        try:
            lxmf.send_direct(DELIVERY_HASH, "Hello")
            lxmf.stop()
            resolution_release.set()

            deadline = time.monotonic() + 5
            errors = []
            while time.monotonic() < deadline:
                errors = [
                    line
                    for line in lxmf.poll_event_lines().splitlines()
                    if line.startswith("LXMF_ERROR\t")
                ]
                if errors:
                    break
                time.sleep(0.05)
        finally:
            rns_module.Transport.request_path = original_request_path

        self.assertTrue(
            errors,
            "expected an LXMF_ERROR event when a send is abandoned during shutdown",
        )
        self.assertIn("stopping", protocol.decode(errors[0].split("\t")[1]))

    def test_verified_delivery_emits_direct_message_event(self):
        router = _FakeLXMRouter(None, None, None, None)
        self._with_fakes(router)
        lxmf.start("/private/var/starling", "Starling")

        message = _FakeLXMessage(signature_validated=True, body="Hello 🌍")
        lxmf._delivery_received(message)

        lines = [line for line in lxmf.poll_event_lines().splitlines()
                 if line.startswith("DIRECT_MESSAGE\t")]
        self.assertEqual(len(lines), 1)
        fields = lines[0].split("\t")
        self.assertEqual(protocol.decode(fields[2]), DELIVERY_HASH)
        self.assertEqual(protocol.decode(fields[4]), "Hello 🌍")
        self.assertEqual(fields[6], "0")

    def test_invalid_signature_is_reported_and_not_delivered(self):
        self._with_fakes()
        lxmf.start("/private/var/starling", "Starling")

        message = _FakeLXMessage(
            signature_validated=False,
            unverified_reason=_FakeLXMessage.SIGNATURE_INVALID,
        )
        lxmf._delivery_received(message)

        lines = [line for line in lxmf.poll_event_lines().splitlines()
                 if line.startswith("LXMF_ERROR\t")]
        self.assertEqual(len(lines), 1)
        fields = lines[0].split("\t")
        self.assertIn("invalid sender signature", protocol.decode(fields[1]))
        self.assertFalse(any(
            line.startswith("DIRECT_MESSAGE\t")
            for line in lxmf.poll_event_lines().splitlines()
        ))

    def test_unknown_source_is_withheld_until_identity_resolves(self):
        self._with_fakes()
        lxmf.start("/private/var/starling", "Starling")

        message = _FakeLXMessage(
            signature_validated=False,
            unverified_reason=_FakeLXMessage.SOURCE_UNKNOWN,
        )
        lxmf._delivery_received(message)

        # No path was resolved, so the message must not be delivered yet.
        self.assertFalse(any(
            line.startswith("DIRECT_MESSAGE\t")
            for line in lxmf.poll_event_lines().splitlines()
        ))

    def _event_line_with(self, prefix, deadline=5.0):
        """Poll LXMF event lines until one starts with `prefix` or the deadline passes."""
        import time as _time
        end = _time.time() + deadline
        while _time.time() < end:
            for line in lxmf.poll_event_lines().splitlines():
                if line.startswith(prefix):
                    return line
            _time.sleep(0.05)
        return None

    def test_unknown_source_delivered_after_identity_resolves(self):
        self._with_fakes()
        lxmf.start("/private/var/starling", "Starling")

        # The sender identity resolves during verification, and the re-unpacked
        # message validates, so it should be delivered.
        rns_module = sys.modules["RNS"]
        previous_recall = rns_module.Identity.recall
        rns_module.Identity.recall = lambda hash_hex, **kwargs: _FakeIdentity(IDENTITY_HASH)

        lx_module = sys.modules["LXMF"]
        previous_unpack = lx_module.LXMessage.unpack_from_bytes
        resolved = _FakeLXMessage(signature_validated=True, body="Hello after verification")
        lx_module.LXMessage.unpack_from_bytes = lambda packed: resolved

        try:
            message = _FakeLXMessage(
                signature_validated=False,
                unverified_reason=_FakeLXMessage.SOURCE_UNKNOWN,
            )
            lxmf._delivery_received(message)

            line = self._event_line_with("DIRECT_MESSAGE\t")
            self.assertIsNotNone(line, "expected the resolved unknown-source message to be delivered")
            fields = line.split("\t")
            self.assertEqual(protocol.decode(fields[4]), "Hello after verification")
            self.assertEqual(fields[6], "0")
        finally:
            rns_module.Identity.recall = previous_recall
            lx_module.LXMessage.unpack_from_bytes = previous_unpack

    def test_unknown_source_resolves_but_invalid_signature_is_rejected(self):
        self._with_fakes()
        lxmf.start("/private/var/starling", "Starling")

        # The sender identity resolves, but the re-unpacked message still has an
        # invalid signature, so it must be rejected and not delivered.
        rns_module = sys.modules["RNS"]
        previous_recall = rns_module.Identity.recall
        rns_module.Identity.recall = lambda hash_hex, **kwargs: _FakeIdentity(IDENTITY_HASH)

        lx_module = sys.modules["LXMF"]
        previous_unpack = lx_module.LXMessage.unpack_from_bytes
        resolved = _FakeLXMessage(
            signature_validated=False,
            unverified_reason=_FakeLXMessage.SIGNATURE_INVALID,
        )
        lx_module.LXMessage.unpack_from_bytes = lambda packed: resolved

        try:
            message = _FakeLXMessage(
                signature_validated=False,
                unverified_reason=_FakeLXMessage.SOURCE_UNKNOWN,
            )
            lxmf._delivery_received(message)

            line = self._event_line_with("LXMF_ERROR\t")
            self.assertIsNotNone(line, "expected an error when the resolved sender's signature is still invalid")
            self.assertIn(
                "invalid sender signature after resolving",
                protocol.decode(line.split("\t")[1]),
            )
            self.assertFalse(any(
                line.startswith("DIRECT_MESSAGE\t")
                for line in lxmf.poll_event_lines().splitlines()
            ))
        finally:
            rns_module.Identity.recall = previous_recall
            lx_module.LXMessage.unpack_from_bytes = previous_unpack

    def test_unknown_source_withheld_after_resolution_timeout(self):
        self._with_fakes()
        lxmf.start("/private/var/starling", "Starling")

        # The sender identity never resolves within the verification deadline, so
        # the message must be withheld and the failure reported instead of
        # silently forgotten.
        rns_module = sys.modules["RNS"]
        previous_recall = rns_module.Identity.recall
        rns_module.Identity.recall = lambda hash_hex, **kwargs: None

        # Fast-forward past the 60-second verification deadline without waiting.
        original_monotonic = time.monotonic
        ticks = iter([0.0, 61.0])
        time.monotonic = lambda: next(ticks)
        try:
            message = _FakeLXMessage(
                signature_validated=False,
                unverified_reason=_FakeLXMessage.SOURCE_UNKNOWN,
            )
            lxmf._delivery_received(message)

            end = time.time() + 5
            errors = []
            while time.time() < end:
                errors = [line for line in lxmf.poll_event_lines().splitlines()
                          if line.startswith("LXMF_ERROR\t")]
                if errors:
                    break
                time.sleep(0.05)
        finally:
            time.monotonic = original_monotonic
            rns_module.Identity.recall = previous_recall

        self.assertTrue(
            errors,
            "expected a withheld error when the sender identity never resolves",
        )
        self.assertTrue(
            any("withheld" in protocol.decode(line.split("\t")[1]) for line in errors),
        )
        self.assertFalse(any(
            line.startswith("DIRECT_MESSAGE\t")
            for line in lxmf.poll_event_lines().splitlines()
        ))

    def test_stop_clears_adapter_state(self):
        router = _FakeLXMRouter(None, None, None, None)
        self._with_fakes(router)
        lxmf.start("/private/var/starling", "Starling")
        lxmf.stop()

        self.assertEqual(lxmf.local_address(), "")
        self.assertEqual(lxmf.identity_hash(), "")

    def test_set_display_name_announces_updated_name(self):
        router = _FakeLXMRouter(None, None, None, None)
        self._with_fakes(router)
        lxmf.start("/private/var/starling", "Starling")

        lxmf.set_display_name("Nick 🌍")

        self.assertEqual(lxmf._display_name, "Nick 🌍")
        self.assertEqual(router.announced, lxmf._delivery_destination.hash)


if __name__ == "__main__":
    unittest.main()
