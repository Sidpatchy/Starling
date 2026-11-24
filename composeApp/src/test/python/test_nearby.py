"""Nearby-people regressions that need no radio, network, or Bluetooth adapter."""

import importlib.util
import queue
import sys
import time
import types
import unittest

import starling_bridge_protocol as protocol
import starling_lxmf as lxmf
import starling_nearby as nearby

REFERENCE_AVAILABLE = (
    importlib.util.find_spec("RNS") is not None and importlib.util.find_spec("LXMF") is not None
)

LOCAL_ADDRESS = "d1" * 16
PEER_ADDRESS = "a7" * 16
OTHER_ADDRESS = "b8" * 16


class _Clock:
    def __init__(self, now=1_000_000.0):
        self.now = now

    def __call__(self):
        return self.now


class NearbyPeersTest(unittest.TestCase):
    def setUp(self):
        self.clock = _Clock()
        self.peers = nearby.NearbyPeers(clock=self.clock)

    def test_first_announce_creates_an_entry_for_its_interface(self):
        entry = self.peers.heard(PEER_ADDRESS, "Wren", nearby.KIND_TCP, 3)

        self.assertEqual(entry["address"], PEER_ADDRESS)
        self.assertEqual(entry["name"], "Wren")
        self.assertEqual(entry["network_heard"], self.clock.now)
        self.assertEqual(entry["network_hops"], 3)
        self.assertEqual(entry["network_kind"], nearby.KIND_TCP)
        self.assertEqual(entry["ble_heard"], 0.0)

    def test_same_address_on_bluetooth_and_network_is_one_entry_with_both_readings(self):
        self.peers.heard(PEER_ADDRESS, "Wren", nearby.KIND_TCP, 4)
        self.clock.now += 30
        entry = self.peers.heard(PEER_ADDRESS, "Wren", nearby.KIND_BLE, 1)

        self.assertEqual(len(self.peers.entries()), 1)
        self.assertEqual(entry["ble_heard"], self.clock.now)
        self.assertEqual(entry["ble_hops"], 1)
        self.assertEqual(entry["network_heard"], self.clock.now - 30)
        self.assertEqual(entry["network_hops"], 4)
        self.assertEqual(entry["network_kind"], nearby.KIND_TCP)

    def test_a_later_announce_updates_the_reading_and_keeps_a_known_name(self):
        self.peers.heard(PEER_ADDRESS, "Wren", nearby.KIND_RNODE, 2)
        self.clock.now += 120
        entry = self.peers.heard(PEER_ADDRESS, "", nearby.KIND_TCP, 5)

        self.assertEqual(entry["name"], "Wren")
        self.assertEqual(entry["network_heard"], self.clock.now)
        self.assertEqual(entry["network_hops"], 5)
        self.assertEqual(entry["network_kind"], nearby.KIND_TCP)

        renamed = self.peers.heard(PEER_ADDRESS, "Wren II", nearby.KIND_TCP, 5)
        self.assertEqual(renamed["name"], "Wren II")

    def test_bluetooth_reading_expires_after_an_hour_and_the_network_reading_remains(self):
        self.peers.heard(PEER_ADDRESS, "Wren", nearby.KIND_TCP, 2)
        self.peers.heard(PEER_ADDRESS, "Wren", nearby.KIND_BLE, 1)

        self.clock.now += nearby.BLE_EXPIRY_SECONDS - 1
        self.assertEqual(self.peers.entries()[0]["ble_hops"], 1)

        self.clock.now += 1
        entry = self.peers.entries()[0]
        self.assertEqual(entry["ble_heard"], 0.0)
        self.assertEqual(entry["network_hops"], 2)

    def test_bluetooth_only_entry_is_dropped_an_hour_after_it_was_last_heard(self):
        self.peers.heard(PEER_ADDRESS, "Wren", nearby.KIND_BLE, 1)
        self.clock.now += nearby.BLE_EXPIRY_SECONDS
        self.assertEqual(self.peers.entries(), [])

    def test_network_entry_is_dropped_three_days_after_it_was_last_heard(self):
        self.peers.heard(PEER_ADDRESS, "Wren", nearby.KIND_TCP, 2)
        self.clock.now += nearby.NETWORK_EXPIRY_SECONDS - 1
        self.assertEqual(len(self.peers.entries()), 1)
        self.clock.now += 1
        self.assertEqual(self.peers.entries(), [])

    def test_only_the_fifty_most_recent_network_people_are_kept(self):
        for index in range(nearby.NETWORK_LIMIT + 5):
            self.clock.now += 1
            self.peers.heard("{:032x}".format(index), "Peer {}".format(index), nearby.KIND_TCP, 2)

        addresses = [entry["address"] for entry in self.peers.entries()]
        self.assertEqual(len(addresses), nearby.NETWORK_LIMIT)
        self.assertEqual(addresses[0], "{:032x}".format(nearby.NETWORK_LIMIT + 4))
        self.assertNotIn("{:032x}".format(4), addresses)
        self.assertIn("{:032x}".format(5), addresses)

    def test_bluetooth_people_do_not_count_toward_the_network_limit(self):
        for index in range(nearby.NETWORK_LIMIT + 10):
            self.clock.now += 1
            self.peers.heard("{:032x}".format(index), "", nearby.KIND_BLE, 1)
        for index in range(1000, 1000 + nearby.NETWORK_LIMIT):
            self.clock.now += 1
            self.peers.heard("{:032x}".format(index), "", nearby.KIND_TCP, 2)

        self.assertEqual(len(self.peers.entries()), 2 * nearby.NETWORK_LIMIT + 10)


class _FakeIdentity:
    def __init__(self, delivery_address, other_address="ee" * 16):
        self.hash = bytes.fromhex("b2" * 16)
        self.delivery_hash = bytes.fromhex(delivery_address)
        self.other_hash = bytes.fromhex(other_address)


class _FakeDestination:
    def __init__(self):
        self.hash = bytes.fromhex(LOCAL_ADDRESS)
        self.display_name = ""

    @staticmethod
    def hash_for(identity, app_name, *aspects):
        if (app_name,) + aspects == ("lxmf", "delivery"):
            return identity.delivery_hash
        return identity.other_hash


class _FakeRouter:
    def __init__(self):
        self.announcements = []

    def register_delivery_identity(self, identity, display_name=""):
        return _FakeDestination()

    def register_delivery_callback(self, callback):
        pass

    def announce(self, destination_hash):
        self.announcements.append(destination_hash)

    def exit_handler(self):
        pass


class TCPClientInterface:
    pass


class RNodeInterface:
    pass


class AutoInterface:
    pass


class _FakeTransport:
    def __init__(self):
        self.handlers = []
        self.interfaces = {}
        self.hops = {}

    def register_announce_handler(self, handler):
        self.handlers.append(handler)

    def deregister_announce_handler(self, handler):
        while handler in self.handlers:
            self.handlers.remove(handler)

    def next_hop_interface(self, destination_hash):
        return self.interfaces.get(destination_hash)

    def hops_to(self, destination_hash):
        return self.hops.get(destination_hash, 128)


class NearbyAnnounceTest(unittest.TestCase):
    def setUp(self):
        self.router = _FakeRouter()
        self.transport = _FakeTransport()
        rns_module = types.ModuleType("RNS")
        rns_module.Transport = self.transport
        rns_module.Destination = types.SimpleNamespace(hash=_FakeDestination.hash_for)
        rns_module.LOG_DEBUG = 7
        rns_module.log = lambda *args, **kwargs: None
        lxmf_module = types.ModuleType("LXMF")
        lxmf_module.LXMRouter = lambda *args, **kwargs: self.router
        lxmf_module.display_name_from_app_data = lambda data: data.decode("utf-8") if data else None
        identity_module = types.ModuleType("starling_identity")
        identity_module.load_identity = lambda config_dir: _FakeIdentity(LOCAL_ADDRESS)
        rns_bridge_module = types.ModuleType("starling_rns")
        rns_bridge_module.construct_without_required_process_signals = lambda factory: factory()
        self._replaced = []
        for name, module in (
            ("RNS", rns_module), ("LXMF", lxmf_module),
            ("starling_identity", identity_module), ("starling_rns", rns_bridge_module),
        ):
            self._replaced.append((name, sys.modules.get(name)))
            sys.modules[name] = module
        self._delay = lxmf.BLE_ANNOUNCE_DELAY_SECONDS
        lxmf.BLE_ANNOUNCE_DELAY_SECONDS = 0
        lxmf._events = queue.Queue()
        lxmf._shutdown.clear()
        lxmf.start("/private/var/starling", "Starling")
        self.router.announcements.clear()
        lxmf._events = queue.Queue()

    def tearDown(self):
        try:
            lxmf.stop()
        finally:
            lxmf.BLE_ANNOUNCE_DELAY_SECONDS = self._delay
            lxmf._events = queue.Queue()
            lxmf._shutdown.clear()
            for name, previous in self._replaced:
                if previous is None:
                    sys.modules.pop(name, None)
                else:
                    sys.modules[name] = previous

    def _announce(self, address, name="Wren", interface=None, hops=1, aspect_matches=True):
        destination_hash = bytes.fromhex(address)
        identity = _FakeIdentity(address if aspect_matches else OTHER_ADDRESS, other_address=address)
        self.transport.interfaces[destination_hash] = interface or TCPClientInterface()
        self.transport.hops[destination_hash] = hops
        self.transport.handlers[0].received_announce(destination_hash, identity, name.encode("utf-8"))

    def _nearby_events(self):
        return [event for event in lxmf.poll_events() if event["kind"] == "nearby_peer"]

    def _wait_for_announcements(self, count):
        deadline = time.monotonic() + 2
        while len(self.router.announcements) < count and time.monotonic() < deadline:
            time.sleep(0.01)
        return len(self.router.announcements)

    def test_start_registers_one_handler_for_lxmf_delivery_announces_only(self):
        self.assertEqual(len(self.transport.handlers), 1)
        handler = self.transport.handlers[0]
        self.assertEqual(handler.aspect_filter, "lxmf.delivery")
        self.assertFalse(getattr(handler, "receive_path_responses", False))

    def test_stop_removes_the_handler_and_forgets_who_was_heard(self):
        self._announce(PEER_ADDRESS)
        lxmf.stop()
        self.assertEqual(self.transport.handlers, [])
        self.assertEqual(lxmf._nearby.entries(), [])

    def test_delivery_announce_emits_the_person_with_interface_and_hops(self):
        self._announce(PEER_ADDRESS, name="Wren", interface=RNodeInterface(), hops=3)

        events = self._nearby_events()
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["address"], PEER_ADDRESS)
        self.assertEqual(events[0]["name"], "Wren")
        self.assertEqual(events[0]["network_kind"], nearby.KIND_RNODE)
        self.assertEqual(events[0]["network_hops"], 3)
        self.assertGreater(events[0]["network_heard"], 0)
        self.assertEqual(events[0]["ble_heard"], 0.0)

    def test_own_announce_is_never_listed(self):
        self._announce(LOCAL_ADDRESS)
        self.assertEqual(self._nearby_events(), [])
        self.assertEqual(lxmf._nearby.entries(), [])

    def test_announce_for_another_aspect_of_the_identity_is_ignored(self):
        self._announce(PEER_ADDRESS, aspect_matches=False)
        self.assertEqual(self._nearby_events(), [])

    def test_announce_without_a_known_identity_is_ignored(self):
        self.transport.handlers[0].received_announce(bytes.fromhex(PEER_ADDRESS), None, b"Wren")
        self.assertEqual(self._nearby_events(), [])

    def test_unreadable_announce_data_lists_the_person_without_a_name(self):
        destination_hash = bytes.fromhex(PEER_ADDRESS)
        self.transport.interfaces[destination_hash] = TCPClientInterface()
        self.transport.hops[destination_hash] = 2
        self.transport.handlers[0].received_announce(destination_hash, _FakeIdentity(PEER_ADDRESS), b"\xff\xfe")

        events = self._nearby_events()
        self.assertEqual([(event["address"], event["name"]) for event in events], [(PEER_ADDRESS, "")])

    def test_interfaces_are_classified_by_class(self):
        class SpawnedTcpClient(TCPClientInterface):
            pass

        self.assertEqual(lxmf._interface_kind(SpawnedTcpClient()), nearby.KIND_TCP)
        self.assertEqual(lxmf._interface_kind(RNodeInterface()), nearby.KIND_RNODE)
        self.assertEqual(lxmf._interface_kind(AutoInterface()), "AutoInterface")
        self.assertEqual(lxmf._interface_kind(None), "")

    def test_bluetooth_peer_interfaces_are_recognised_by_their_parent_class(self):
        class FakeBleInterface:
            pass

        class FakePeerInterface:
            def __init__(self, parent):
                self.parent_interface = parent

        ble_module = types.ModuleType("starling_ble_android")
        ble_module._interface_class = FakeBleInterface
        ble_module.set_peer_listener = lambda listener: True
        self._replaced.append(("starling_ble_android", sys.modules.get("starling_ble_android")))
        sys.modules["starling_ble_android"] = ble_module

        self._announce(PEER_ADDRESS, interface=FakePeerInterface(FakeBleInterface()), hops=1)
        self._announce(PEER_ADDRESS, interface=TCPClientInterface(), hops=4)

        events = self._nearby_events()
        self.assertEqual(len(events), 2)
        self.assertGreater(events[0]["ble_heard"], 0)
        self.assertEqual(events[0]["ble_hops"], 1)
        self.assertEqual(events[0]["network_heard"], 0.0)
        # The second announce keeps the Bluetooth reading and adds the network one.
        self.assertEqual(events[1]["ble_heard"], events[0]["ble_heard"])
        self.assertEqual(events[1]["network_kind"], nearby.KIND_TCP)
        self.assertEqual(events[1]["network_hops"], 4)
        self.assertEqual(len(lxmf._nearby.entries()), 1)

    def test_event_line_carries_both_readings(self):
        self._announce(PEER_ADDRESS, name="Wren 🌍", interface=TCPClientInterface(), hops=2)

        fields = lxmf.poll_event_lines().split("\t")
        self.assertEqual(fields[0], "NEARBY_PEER")
        self.assertEqual(protocol.decode(fields[1]), PEER_ADDRESS)
        self.assertEqual(protocol.decode(fields[2]), "Wren 🌍")
        self.assertEqual(fields[3:5], ["0", "0"])
        self.assertGreater(int(fields[5]), 0)
        self.assertEqual(fields[6], "2")
        self.assertEqual(protocol.decode(fields[7]), nearby.KIND_TCP)

    def test_manual_announce_announces_the_delivery_destination(self):
        lxmf.announce()
        lxmf.announce()
        self.assertEqual(self.router.announcements, [bytes.fromhex(LOCAL_ADDRESS)] * 2)

    def test_manual_announce_command_reaches_the_adapter(self):
        protocol.dispatch_lxmf("LXMF_ANNOUNCE", lxmf)
        self.assertEqual(self.router.announcements, [bytes.fromhex(LOCAL_ADDRESS)])

    def test_manual_announce_before_start_raises(self):
        lxmf.stop()
        with self.assertRaises(RuntimeError):
            lxmf.announce()

    def test_new_bluetooth_peer_announces_at_most_once_per_five_minutes(self):
        self.assertEqual(lxmf.BLE_ANNOUNCE_INTERVAL_SECONDS, 300)
        self.assertTrue(lxmf.announce_for_new_ble_peer())
        self.assertEqual(self._wait_for_announcements(1), 1)

        self.assertFalse(lxmf.announce_for_new_ble_peer())
        self.assertFalse(lxmf.announce_for_new_ble_peer())

        lxmf._last_ble_announce -= lxmf.BLE_ANNOUNCE_INTERVAL_SECONDS - 1
        self.assertFalse(lxmf.announce_for_new_ble_peer())
        lxmf._last_ble_announce -= 1
        self.assertTrue(lxmf.announce_for_new_ble_peer())
        self.assertEqual(self._wait_for_announcements(2), 2)

    def test_bluetooth_announce_limit_resets_with_each_node_run(self):
        self.assertTrue(lxmf.announce_for_new_ble_peer())
        self._wait_for_announcements(1)
        lxmf.stop()
        self.assertFalse(lxmf.announce_for_new_ble_peer())

        lxmf.start("/private/var/starling", "Starling")
        self.router.announcements.clear()
        self.assertTrue(lxmf.announce_for_new_ble_peer())
        self.assertEqual(self._wait_for_announcements(1), 1)

    def test_manual_announce_is_not_rate_limited_by_the_bluetooth_announce(self):
        self.assertTrue(lxmf.announce_for_new_ble_peer())
        self._wait_for_announcements(1)
        lxmf.announce()
        self.assertEqual(len(self.router.announcements), 2)

    def test_start_registers_the_bluetooth_peer_listener_when_the_driver_is_loaded(self):
        listeners = []
        ble_module = types.ModuleType("starling_ble_android")
        ble_module._interface_class = None
        ble_module.set_peer_listener = listeners.append
        self._replaced.append(("starling_ble_android", sys.modules.get("starling_ble_android")))
        sys.modules["starling_ble_android"] = ble_module

        lxmf.stop()
        lxmf.start("/private/var/starling", "Starling")
        self.assertEqual(listeners, [None, lxmf.announce_for_new_ble_peer])
        lxmf.stop()
        self.assertIsNone(listeners[-1])


@unittest.skipUnless(REFERENCE_AVAILABLE, "Reference RNS and LXMF packages are not installed")
class ReferenceAnnounceContractTest(unittest.TestCase):
    def test_handler_matches_the_reference_announce_callback_contract(self):
        import inspect

        import LXMF
        import RNS

        handler = lxmf._NearbyAnnounceHandler()
        # RNS 1.5.7 dispatches on the parameter count and passes these keywords.
        self.assertEqual(
            list(inspect.signature(handler.received_announce).parameters),
            ["destination_hash", "announced_identity", "app_data"],
        )
        self.assertEqual(handler.aspect_filter, LXMF.APP_NAME + ".delivery")
        RNS.Transport.register_announce_handler(handler)
        try:
            self.assertIn(handler, RNS.Transport.announce_handlers)
        finally:
            RNS.Transport.deregister_announce_handler(handler)
        self.assertNotIn(handler, RNS.Transport.announce_handlers)

    def test_reference_helpers_identify_a_delivery_destination_and_its_name(self):
        import LXMF
        import RNS

        identity = RNS.Identity()
        delivery_hash = RNS.Destination.hash(identity, "lxmf", "delivery")
        self.assertEqual(delivery_hash, RNS.Destination.hash_from_name_and_identity("lxmf.delivery", identity))
        self.assertNotEqual(delivery_hash, RNS.Destination.hash(identity, "lxmf", "propagation"))
        app_data = RNS.vendor.umsgpack.packb(["Wren".encode("utf-8"), None])
        self.assertEqual(LXMF.display_name_from_app_data(app_data), "Wren")
        self.assertIsNone(LXMF.display_name_from_app_data(None))


if __name__ == "__main__":
    unittest.main()
