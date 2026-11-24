"""BLE peer interface regressions that need no Bluetooth adapter or network."""

import importlib.util
import json
import logging
import os
import tempfile
import unittest
from unittest.mock import Mock, patch

import starling_rns as rns_bridge

DRIVER_AVAILABLE = importlib.util.find_spec("ble_reticulum") is not None
REFERENCE_AVAILABLE = DRIVER_AVAILABLE and importlib.util.find_spec("RNS") is not None

if DRIVER_AVAILABLE:
    import starling_ble_android as ble
    from ble_reticulum.bluetooth_driver import DriverState

SERVICE_UUID = "37145b00-442d-4a94-917f-8f42c5da28e3"
RX_UUID = "37145b00-442d-4a94-917f-8f42c5da28e5"
TX_UUID = "37145b00-442d-4a94-917f-8f42c5da28e4"
IDENTITY_UUID = "37145b00-442d-4a94-917f-8f42c5da28e6"
LOCAL_IDENTITY = bytes(range(16))
PEER_IDENTITY = bytes(range(16, 32))
PEER = "AA:BB:CC:DD:EE:01"
OTHER_PEER = "AA:BB:CC:DD:EE:02"


class _FakeBridge:
    """Stands in for the Kotlin bridge and records what the driver asks of it."""

    def __init__(self):
        self.calls = []
        self.listener = None
        self.start_error = None
        self.connect_error = None
        self.send_result = True
        self.local_address = None
        self.waits_for = set()

    def start(self, service_uuid, rx_uuid, tx_uuid, identity_uuid, connection_timeout, max_peers, listener):
        self.calls.append(("start", service_uuid, rx_uuid, tx_uuid, identity_uuid, connection_timeout, max_peers))
        self.listener = listener
        return self.start_error

    def stop(self):
        self.calls.append(("stop",))

    def setIdentity(self, identity):
        self.calls.append(("setIdentity", identity))

    def startScanning(self, power_mode):
        self.calls.append(("startScanning", power_mode))
        return None

    def stopScanning(self):
        self.calls.append(("stopScanning",))

    def startAdvertising(self):
        self.calls.append(("startAdvertising",))
        return None

    def stopAdvertising(self):
        self.calls.append(("stopAdvertising",))

    def connect(self, address):
        self.calls.append(("connect", address))
        return self.connect_error

    def disconnect(self, address):
        self.calls.append(("disconnect", address))

    def writeRx(self, address, data):
        self.calls.append(("writeRx", address, data))
        return self.send_result

    def notifyTx(self, address, data):
        self.calls.append(("notifyTx", address, data))
        return self.send_result

    def localAddress(self):
        return self.local_address

    def shouldInitiate(self, address):
        return address not in self.waits_for

    def named(self, name):
        return [call for call in self.calls if call[0] == name]


class _Events:
    def __init__(self, driver):
        self.items = []
        driver.on_device_discovered = lambda device: self.items.append(("discovered", device))
        driver.on_device_connected = lambda address, identity: self.items.append(("connected", address, identity))
        driver.on_device_disconnected = lambda address: self.items.append(("disconnected", address))
        driver.on_data_received = lambda address, data: self.items.append(("data", address, data))
        driver.on_mtu_negotiated = lambda address, mtu: self.items.append(("mtu", address, mtu))
        driver.on_error = lambda severity, message, error=None: self.items.append(("error", severity, message))

    def named(self, name):
        return [item for item in self.items if item[0] == name]


class _ReticulumDefaults:
    def __getattr__(self, name):
        return lambda *arguments: 1


class BlePeerConfigTest(unittest.TestCase):
    def test_ble_peer_flag_accepts_only_booleans(self):
        self.assertTrue(rns_bridge._normalize_ble_peers(True))
        self.assertFalse(rns_bridge._normalize_ble_peers(False))
        self.assertTrue(rns_bridge._normalize_ble_peers(" True "))
        self.assertFalse(rns_bridge._normalize_ble_peers("false"))
        for invalid in ("yes", "", "1", 1, None, []):
            with self.assertRaises(ValueError):
                rns_bridge._normalize_ble_peers(invalid)

    @unittest.skipUnless(REFERENCE_AVAILABLE, "Reference RNS and ble-reticulum packages are not installed")
    def test_config_omits_the_ble_interface_unless_enabled(self):
        from RNS.vendor.configobj import ConfigObj

        peer = {"host": "mesh.example", "port": 4242, "ifac_network_name": "", "ifac_passphrase": ""}
        with tempfile.TemporaryDirectory() as config_dir:
            rns_bridge._write_config(config_dir, "embedded", [peer], [], None, android_platform=True)
            config = ConfigObj(os.path.join(config_dir, "config"))
            self.assertEqual(list(config["interfaces"]), ["Starling TCP 1"])
            self.assertFalse(os.path.exists(os.path.join(config_dir, "interfaces")))

    @unittest.skipUnless(REFERENCE_AVAILABLE, "Reference RNS and ble-reticulum packages are not installed")
    def test_config_adds_the_ble_interface_and_its_loader_when_enabled(self):
        from RNS.vendor.configobj import ConfigObj

        with tempfile.TemporaryDirectory() as config_dir:
            rns_bridge._write_config(config_dir, "embedded", [], [], None, android_platform=True, ble_peers=True)
            config = ConfigObj(os.path.join(config_dir, "config"))
            self.assertEqual(config["reticulum"]["enable_transport"], "no")
            interface = config["interfaces"]["Starling BLE Peers"]
            self.assertEqual(interface["type"], "StarlingBLEPeerInterface")
            self.assertEqual(interface["enabled"], "yes")
            self.assertEqual(interface["enable_central"], "yes")
            self.assertEqual(interface["enable_peripheral"], "yes")

            # RNS executes <config>/interfaces/<type>.py and reads interface_class from it.
            loader_path = os.path.join(config_dir, "interfaces", "StarlingBLEPeerInterface.py")
            with open(loader_path, encoding="ascii") as loader_file:
                loader_globals = {}
                exec(loader_file.read(), loader_globals)
            self.assertIs(loader_globals["interface_class"], ble.load_interface_class())

    @unittest.skipUnless(REFERENCE_AVAILABLE, "Reference RNS and ble-reticulum packages are not installed")
    def test_config_ignores_ble_peers_for_an_existing_shared_instance(self):
        from RNS.vendor.configobj import ConfigObj

        with tempfile.TemporaryDirectory() as config_dir:
            rns_bridge._write_config(
                config_dir, "existing_shared_instance", [], [], "11" * 16, android_platform=True, ble_peers=True,
            )
            config = ConfigObj(os.path.join(config_dir, "config"))
            self.assertNotIn("interfaces", config)


@unittest.skipUnless(REFERENCE_AVAILABLE, "Reference RNS and ble-reticulum packages are not installed")
class BlePeerStartValidationTest(unittest.TestCase):
    def setUp(self):
        self.state = (
            rns_bridge._reticulum, rns_bridge._mode, rns_bridge._tcp_peers, rns_bridge._rnode_interfaces,
            rns_bridge._ble_peers, rns_bridge._rpc_key_hex, rns_bridge._config_dir,
        )
        rns_bridge._reticulum = None
        self.addCleanup(self._restore)
        self.addCleanup(ble.clear_bridge)
        ble.clear_bridge()

    def _restore(self):
        (
            rns_bridge._reticulum, rns_bridge._mode, rns_bridge._tcp_peers, rns_bridge._rnode_interfaces,
            rns_bridge._ble_peers, rns_bridge._rpc_key_hex, rns_bridge._config_dir,
        ) = self.state

    def _start(self, config_dir, android=True, **arguments):
        import RNS

        reticulum = Mock()
        with patch("starling_identity.load_identity"), \
                patch.object(RNS.vendor.platformutils, "is_android", return_value=android), \
                patch.object(RNS, "Reticulum", reticulum), \
                patch.object(os, "umask"):
            result = rns_bridge.start_node(config_dir, **arguments)
        return result, reticulum

    def test_ble_peers_alone_are_enough_to_start_the_embedded_node(self):
        from RNS.vendor.configobj import ConfigObj

        ble.set_bridge(_FakeBridge())
        with tempfile.TemporaryDirectory() as config_dir:
            result, reticulum = self._start(config_dir, mode="embedded", ble_peers=True)
            config = ConfigObj(os.path.join(config_dir, "config"))
        reticulum.assert_called_once()
        self.assertIn("Starling BLE Peers", config["interfaces"])
        self.assertTrue(rns_bridge._ble_peers)
        self.assertTrue(json.loads(result)["ble_peers"]["enabled"])

    def test_string_flags_from_the_service_are_normalized(self):
        ble.set_bridge(_FakeBridge())
        with tempfile.TemporaryDirectory() as config_dir:
            self._start(config_dir, mode="embedded", ble_peers="true")
        self.assertIs(rns_bridge._ble_peers, True)

    def test_embedded_node_without_any_interface_is_still_rejected(self):
        with tempfile.TemporaryDirectory() as config_dir:
            with self.assertRaisesRegex(ValueError, "Add a TCP peer or an RNode interface"):
                self._start(config_dir, mode="embedded", ble_peers=False)

    def test_invalid_ble_peer_values_are_rejected(self):
        with tempfile.TemporaryDirectory() as config_dir:
            with self.assertRaisesRegex(ValueError, "must be true or false"):
                self._start(config_dir, mode="embedded", ble_peers="maybe")

    def test_ble_peers_are_rejected_for_an_existing_shared_instance(self):
        ble.set_bridge(_FakeBridge())
        with tempfile.TemporaryDirectory() as config_dir:
            with self.assertRaisesRegex(ValueError, "embedded node"):
                self._start(config_dir, mode="existing_shared_instance", rpc_key_hex="11" * 16, ble_peers=True)

    def test_ble_peers_are_rejected_off_android(self):
        ble.set_bridge(_FakeBridge())
        with tempfile.TemporaryDirectory() as config_dir:
            with self.assertRaisesRegex(ValueError, "only available on Android"):
                self._start(config_dir, android=False, mode="embedded", ble_peers=True)

    def test_ble_peers_require_the_platform_bridge(self):
        with tempfile.TemporaryDirectory() as config_dir:
            with self.assertRaisesRegex(ValueError, "bridge is not available"):
                self._start(config_dir, mode="embedded", ble_peers=True)

    def test_status_reports_ble_peers_as_disabled_by_default(self):
        peers = json.dumps([{"host": "mesh.example", "port": 4242}])
        with tempfile.TemporaryDirectory() as config_dir:
            result, _ = self._start(config_dir, mode="embedded", tcp_peers=peers)
        self.assertEqual(json.loads(result)["ble_peers"], {"enabled": False, "online": False, "peer_count": 0})


@unittest.skipUnless(DRIVER_AVAILABLE, "The ble-reticulum package is not installed")
class AndroidBleDriverTest(unittest.TestCase):
    def setUp(self):
        self.bridge = _FakeBridge()
        self.driver = ble.AndroidBLEDriver(bridge=self.bridge, max_peers=2, min_rssi=-80, connection_timeout=12)
        self.events = _Events(self.driver)
        self.driver.start(SERVICE_UUID, RX_UUID, TX_UUID, IDENTITY_UUID)
        self.driver.set_identity(LOCAL_IDENTITY)
        self.bridge.calls = [call for call in self.bridge.calls if call[0] != "setIdentity"]

    def test_start_hands_the_protocol_uuids_and_listener_to_the_bridge(self):
        self.assertEqual(
            self.bridge.calls[0], ("start", SERVICE_UUID, RX_UUID, TX_UUID, IDENTITY_UUID, 12.0, 2),
        )
        self.assertIs(self.bridge.listener, self.driver)

    def test_start_failure_is_raised_and_leaves_the_driver_stopped(self):
        bridge = _FakeBridge()
        bridge.start_error = "Turn on Bluetooth to use Bluetooth peers."
        driver = ble.AndroidBLEDriver(bridge=bridge)
        with self.assertRaisesRegex(RuntimeError, "Turn on Bluetooth"):
            driver.start(SERVICE_UUID, RX_UUID, TX_UUID, IDENTITY_UUID)
        with self.assertRaises(RuntimeError):
            driver.start_scanning()

    def test_driver_requires_a_bridge(self):
        ble.clear_bridge()
        with self.assertRaises(RuntimeError):
            ble.AndroidBLEDriver()

    def test_registered_bridge_is_used_by_default(self):
        self.addCleanup(ble.clear_bridge)
        ble.set_bridge(self.bridge)
        self.assertIs(ble.AndroidBLEDriver(discovery_interval=5.0, adapter_index=0)._bridge, self.bridge)

    def test_identity_must_be_sixteen_bytes(self):
        with self.assertRaises(ValueError):
            self.driver.set_identity(b"short")
        with self.assertRaises(TypeError):
            self.driver.set_identity("0" * 16)
        self.driver.set_identity(LOCAL_IDENTITY)
        self.assertEqual(self.bridge.named("setIdentity"), [("setIdentity", LOCAL_IDENTITY)])

    def test_scanning_and_advertising_update_driver_state(self):
        from ble_reticulum.bluetooth_driver import DriverState

        self.driver.set_power_mode("saver")
        self.driver.start_scanning()
        self.assertEqual(self.bridge.named("startScanning"), [("startScanning", "saver")])
        self.assertEqual(self.driver.state, DriverState.SCANNING)
        self.driver.start_advertising(None, PEER_IDENTITY)
        self.assertEqual(self.bridge.named("setIdentity"), [("setIdentity", PEER_IDENTITY)])
        self.driver.start_advertising(None, PEER_IDENTITY)
        self.assertEqual(len(self.bridge.named("setIdentity")), 1)
        self.driver.stop_scanning()
        self.assertEqual(self.driver.state, DriverState.ADVERTISING)
        self.driver.stop_advertising()
        self.assertEqual(self.driver.state, DriverState.IDLE)
        with self.assertRaises(ValueError):
            self.driver.set_power_mode("turbo")

    def test_discovered_devices_are_reported_with_the_service_uuid(self):
        self.bridge.listener.bridge_discovered(PEER, None, -60)
        self.bridge.listener.bridge_discovered(OTHER_PEER, "Field", -95)
        discovered = self.events.named("discovered")
        self.assertEqual(len(discovered), 1)
        device = discovered[0][1]
        self.assertEqual((device.address, device.rssi, device.service_uuids), (PEER, -60, [SERVICE_UUID]))
        self.assertTrue(device.name)

    def test_central_connection_reports_identity_then_mtu_and_sends_with_writes(self):
        self.driver.connect(PEER)
        self.assertIn(PEER, self.driver._connecting_peers)
        self.assertTrue(self.bridge.listener.bridge_identity_read(PEER, list(PEER_IDENTITY)))
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)

        self.assertEqual(self.events.items, [("connected", PEER, PEER_IDENTITY), ("mtu", PEER, 512)])
        self.assertNotIn(PEER, self.driver._connecting_peers)
        self.assertEqual(self.driver.get_peer_role(PEER), "central")
        self.assertEqual(self.driver.get_peer_mtu(PEER), 512)
        self.assertEqual(self.driver.connected_peers, [PEER])
        self.assertEqual(self.driver.read_characteristic(PEER, IDENTITY_UUID), PEER_IDENTITY)

        self.driver.send(PEER, bytearray(b"fragment"))
        self.assertEqual(self.bridge.named("writeRx"), [("writeRx", PEER, b"fragment")])
        self.assertEqual(self.bridge.named("notifyTx"), [])

    def test_peripheral_connection_waits_for_the_handshake_and_sends_with_notifications(self):
        self.bridge.listener.bridge_connected(PEER, "peripheral", None, 182)

        self.assertEqual(self.events.items, [("connected", PEER, None), ("mtu", PEER, 182)])
        self.assertEqual(self.driver.get_peer_role(PEER), "peripheral")
        self.driver.send(PEER, b"fragment")
        self.assertEqual(self.bridge.named("notifyTx"), [("notifyTx", PEER, b"fragment")])
        self.assertEqual(self.bridge.named("writeRx"), [])

    def test_received_data_is_converted_from_signed_java_bytes(self):
        self.bridge.listener.bridge_connected(PEER, "peripheral", None, 182)
        self.bridge.listener.bridge_data(PEER, [-1, 0, 16, -128])
        self.bridge.listener.bridge_data(OTHER_PEER, [1, 2, 3])
        self.assertEqual(self.events.named("data"), [("data", PEER, b"\xff\x00\x10\x80")])

    def test_mtu_changes_are_forwarded_once_per_value(self):
        self.bridge.listener.bridge_connected(PEER, "peripheral", None, 20)
        self.bridge.listener.bridge_mtu_changed(PEER, 244)
        self.bridge.listener.bridge_mtu_changed(PEER, 244)
        self.bridge.listener.bridge_mtu_changed(OTHER_PEER, 244)
        self.assertEqual(self.events.named("mtu"), [("mtu", PEER, 20), ("mtu", PEER, 244)])
        self.assertEqual(self.driver.get_peer_mtu(PEER), 244)

    def test_remote_and_local_disconnects_are_each_reported_once(self):
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        self.bridge.listener.bridge_connected(OTHER_PEER, "peripheral", None, 182)

        self.bridge.listener.bridge_disconnected(PEER)
        self.bridge.listener.bridge_disconnected(PEER)
        self.driver.disconnect(OTHER_PEER)
        self.bridge.listener.bridge_disconnected(OTHER_PEER)
        self.driver.disconnect(OTHER_PEER)

        self.assertEqual(self.events.named("disconnected"), [("disconnected", PEER), ("disconnected", OTHER_PEER)])
        self.assertEqual(self.bridge.named("disconnect"), [("disconnect", OTHER_PEER)])
        self.assertEqual(self.driver.connected_peers, [])
        with self.assertRaises(RuntimeError):
            self.driver.send(PEER, b"late")

    def test_turning_bluetooth_off_drops_every_link_and_pending_attempt(self):
        self.driver.start_scanning()
        self.driver.start_advertising("ignored", LOCAL_IDENTITY)
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        self.driver.connect(OTHER_PEER)
        self.assertEqual(self.driver._connecting_peers, {OTHER_PEER})

        self.bridge.listener.bridge_adapter_state(False)
        self.bridge.listener.bridge_adapter_state(False)

        self.assertEqual(self.events.named("disconnected"), [("disconnected", PEER)])
        self.assertEqual(self.driver.connected_peers, [])
        self.assertEqual(self.driver._connecting_peers, set())
        self.assertIs(self.driver.state, DriverState.IDLE)
        # The links are already gone, so the bridge is not asked to close them,
        # and nothing is reported in the wording the interface backs off on.
        self.assertEqual(self.bridge.named("disconnect"), [])
        self.assertFalse([item for item in self.events.named("error") if "Connection" in item[2]])
        with self.assertRaises(RuntimeError):
            self.driver.send(PEER, b"late")

    def test_bluetooth_coming_back_resumes_scanning_state_and_new_connections(self):
        self.driver.start_scanning()
        self.driver.start_advertising("ignored", LOCAL_IDENTITY)
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        self.bridge.listener.bridge_adapter_state(False)

        self.bridge.listener.bridge_adapter_state(True)

        # The bridge restarts the scan and advertising itself; the driver does not repeat them.
        self.assertEqual(len(self.bridge.named("startScanning")), 1)
        self.assertEqual(len(self.bridge.named("startAdvertising")), 1)
        self.assertIs(self.driver.state, DriverState.SCANNING)
        self.bridge.listener.bridge_discovered(PEER, None, -50)
        self.assertEqual(len(self.events.named("discovered")), 1)
        self.driver.connect(PEER)
        self.assertEqual(self.bridge.named("connect"), [("connect", PEER)])
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        self.assertEqual(self.driver.connected_peers, [PEER])

    def test_adapter_changes_are_ignored_once_the_driver_is_stopped(self):
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        self.driver.stop()
        self.events.items.clear()

        self.bridge.listener.bridge_adapter_state(False)
        self.bridge.listener.bridge_adapter_state(True)

        self.assertEqual(self.events.items, [])

    def test_duplicate_identity_abandons_the_connection_without_a_failure_report(self):
        self.driver.on_duplicate_identity_detected = lambda address, identity: identity == PEER_IDENTITY
        self.driver.connect(PEER)
        self.assertFalse(self.bridge.listener.bridge_identity_read(PEER, list(PEER_IDENTITY)))
        self.assertNotIn(PEER, self.driver._connecting_peers)
        self.assertEqual(self.events.named("error"), [("error", "info", "Duplicate identity rejected for " + PEER + " (MAC rotation)")])
        self.assertTrue(self.bridge.listener.bridge_identity_read(OTHER_PEER, list(LOCAL_IDENTITY)))

    def test_no_connection_is_opened_before_the_local_identity_is_known(self):
        # After a restart the driver and bridge are new, and peers can be
        # discovered before Reticulum has supplied the identity.
        bridge = _FakeBridge()
        driver = ble.AndroidBLEDriver(bridge=bridge)
        events = _Events(driver)
        driver.start(SERVICE_UUID, RX_UUID, TX_UUID, IDENTITY_UUID)
        driver.start_scanning()

        driver.connect(PEER)
        self.assertEqual(bridge.named("connect"), [])
        self.assertEqual(driver._connecting_peers, set())
        self.assertEqual(events.items, [])

        driver.set_identity(LOCAL_IDENTITY)
        driver.connect(PEER)
        names = [call[0] for call in bridge.calls]
        self.assertEqual(bridge.named("connect"), [("connect", PEER)])
        self.assertLess(names.index("setIdentity"), names.index("connect"))

    def _connect_central(self, now):
        self.driver._clock = lambda: now[0]
        self.driver.connect(PEER)
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        self.events.items.clear()

    def test_central_link_is_dropped_when_the_peer_stops_answering(self):
        now = [1000.0]
        self._connect_central(now)

        self.driver.send(PEER, b"one")
        now[0] += 10
        self.driver.send(PEER, b"two")
        now[0] += ble.REPLY_TIMEOUT
        with self.assertRaisesRegex(RuntimeError, "stopped answering"):
            self.driver.send(PEER, b"three")

        self.assertEqual([call[2] for call in self.bridge.named("writeRx")], [b"one", b"two"])
        self.assertEqual(self.bridge.named("disconnect"), [("disconnect", PEER)])
        self.assertIsNone(self.driver.get_peer_role(PEER))
        self.assertEqual(self.events.items, [
            ("error", "warning", "No data from " + PEER + " for 40s while sending; reconnecting"),
            ("disconnected", PEER),
        ])
        # The driver may connect again, which repeats the identity handshake.
        self.driver.connect(PEER)
        self.assertEqual(len(self.bridge.named("connect")), 2)

    def test_central_link_survives_while_the_peer_answers_or_nothing_is_sent(self):
        now = [1000.0]
        self._connect_central(now)

        for _ in range(5):
            self.driver.send(PEER, b"fragment")
            now[0] += ble.REPLY_TIMEOUT * 2
            self.bridge.listener.bridge_data(PEER, [1, 2, 3])
        # Hours of silence in both directions, then a burst sent in one go.
        now[0] += 7200
        for _ in range(10):
            self.driver.send(PEER, b"fragment")
        now[0] += ble.REPLY_TIMEOUT - 1
        self.driver.send(PEER, b"fragment")

        self.assertEqual(self.bridge.named("disconnect"), [])
        self.assertEqual(self.driver.get_peer_role(PEER), "central")

    def test_reply_timeout_doubles_for_a_quiet_peer_and_resets_when_it_is_heard(self):
        now = [1000.0]

        def silent_sends(gap):
            self._connect_central(now)
            self.driver.send(PEER, b"one")
            self.driver.send(PEER, b"two")
            now[0] += gap
            self.driver.send(PEER, b"three")

        with self.assertRaises(RuntimeError):
            silent_sends(ble.REPLY_TIMEOUT)
        silent_sends(ble.REPLY_TIMEOUT)
        now[0] += ble.REPLY_TIMEOUT
        with self.assertRaises(RuntimeError):
            self.driver.send(PEER, b"four")
        self.assertEqual(self.driver._reply_timeouts[PEER_IDENTITY], ble.REPLY_TIMEOUT * 4)

        self._connect_central(now)
        self.bridge.listener.bridge_data(PEER, [1])
        self.assertEqual(self.driver._reply_timeouts, {})
        with self.assertRaises(RuntimeError):
            silent_sends(ble.REPLY_TIMEOUT)

    def test_peripheral_links_are_not_dropped_for_silence(self):
        now = [1000.0]
        self.driver._clock = lambda: now[0]
        self.bridge.listener.bridge_connected(PEER, "peripheral", None, 182)
        for _ in range(5):
            self.driver.send(PEER, b"fragment")
            now[0] += ble.REPLY_TIMEOUT_MAX
        self.assertEqual(len(self.bridge.named("notifyTx")), 5)
        self.assertEqual(self.bridge.named("disconnect"), [])

    def test_connection_failures_use_the_wording_the_interface_backs_off_on(self):
        self.driver.connect(PEER)
        self.driver.connect(PEER)
        self.assertEqual(self.bridge.named("connect"), [("connect", PEER)])
        self.bridge.listener.bridge_connect_failed(PEER, "status 133", False)
        self.assertNotIn(PEER, self.driver._connecting_peers)
        self.driver.connect(PEER)
        self.bridge.listener.bridge_connect_failed(PEER, "", True)
        self.bridge.connect_error = "Bluetooth is off"
        self.driver.connect(PEER)
        self.assertEqual(self.events.named("error"), [
            ("error", "error", "Connection failed to " + PEER + ": status 133"),
            ("error", "warning", "Connection timeout to " + PEER),
            ("error", "error", "Connection failed to " + PEER + ": Bluetooth is off"),
        ])
        self.assertEqual(self.driver._connecting_peers, set())

    def test_connections_stop_at_the_peer_limit(self):
        self.bridge.listener.bridge_connected(PEER, "peripheral", None, 182)
        self.driver.connect(PEER)
        self.driver.connect(OTHER_PEER)
        self.driver.connect("AA:BB:CC:DD:EE:03")
        self.assertEqual(self.bridge.named("connect"), [("connect", OTHER_PEER)])

    def test_connection_is_left_to_the_peer_when_address_ordering_says_to_wait(self):
        self.bridge.waits_for.add(PEER)
        self.driver.connect(PEER)
        self.driver.connect(OTHER_PEER)
        self.assertEqual(self.bridge.named("connect"), [("connect", OTHER_PEER)])
        self.assertEqual(self.driver._connecting_peers, {OTHER_PEER})
        self.assertEqual(self.events.items, [])

    def test_unconfirmed_sends_raise(self):
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        self.bridge.send_result = False
        with self.assertRaises(RuntimeError):
            self.driver.send(PEER, b"fragment")

    def test_local_address_is_unknown_unless_the_platform_reports_one(self):
        self.assertEqual(self.driver.get_local_address(), "00:00:00:00:00:00")
        self.bridge.local_address = "AA:BB:CC:00:00:01"
        self.assertEqual(self.driver.get_local_address(), "AA:BB:CC:00:00:01")

    def test_stop_releases_the_bridge_once_and_ignores_later_events(self):
        self.driver.start_scanning()
        self.driver.connect(OTHER_PEER)
        self.bridge.listener.bridge_connected(PEER, "peripheral", None, 182)
        self.events.items.clear()

        self.driver.stop()
        self.driver.stop()

        from ble_reticulum.bluetooth_driver import DriverState

        self.assertEqual(self.bridge.named("stop"), [("stop",)])
        self.assertEqual(self.driver.state, DriverState.IDLE)
        self.assertEqual(self.driver.connected_peers, [])
        self.assertEqual(self.driver._connecting_peers, set())
        self.assertEqual(self.events.items, [("disconnected", PEER)])

        self.bridge.listener.bridge_discovered(PEER, None, -40)
        self.bridge.listener.bridge_connected(PEER, "peripheral", None, 182)
        self.bridge.listener.bridge_data(PEER, [1])
        self.bridge.listener.bridge_connect_failed(OTHER_PEER, "late", False)
        self.bridge.listener.bridge_error("error", "late")
        self.assertFalse(self.bridge.listener.bridge_identity_read(PEER, list(PEER_IDENTITY)))
        self.driver.connect(PEER)
        self.assertEqual(self.events.items, [("disconnected", PEER)])
        self.assertEqual(self.bridge.named("connect"), [("connect", OTHER_PEER)])


@unittest.skipUnless(REFERENCE_AVAILABLE, "Reference RNS and ble-reticulum packages are not installed")
class StarlingBleInterfaceTest(unittest.TestCase):
    def setUp(self):
        import RNS

        root = logging.getLogger()
        handlers, level = root.handlers[:], root.level
        interfaces = list(RNS.Transport.interfaces)

        def restore():
            root.handlers[:] = handlers
            root.setLevel(level)
            RNS.Transport.interfaces[:] = interfaces

        self.addCleanup(restore)
        self.addCleanup(ble.clear_bridge)
        # Interface construction reads its defaults from the running Reticulum instance.
        instance = patch.object(RNS.Reticulum, "get_instance", return_value=_ReticulumDefaults())
        instance.start()
        self.addCleanup(instance.stop)
        self.bridge = _FakeBridge()
        ble.set_bridge(self.bridge)
        self.owner = Mock()
        self.interface = ble.load_interface_class()(self.owner, {"name": "Starling BLE Peers"})
        self.addCleanup(self.interface.detach)

    def test_interface_starts_the_android_driver_with_the_v2_2_uuids(self):
        self.assertIsInstance(self.interface.driver, ble.AndroidBLEDriver)
        self.assertEqual(self.bridge.calls[0][:5], ("start", SERVICE_UUID, RX_UUID, TX_UUID, IDENTITY_UUID))
        self.assertEqual(self.bridge.named("startScanning"), [("startScanning", "balanced")])
        self.assertTrue(self.interface.enable_peripheral)
        self.assertTrue(self.interface.online)

    def test_peripheral_handshake_creates_a_peer_that_sends_fragments_as_notifications(self):
        import RNS

        self.bridge.listener.bridge_connected(PEER, "peripheral", None, 182)
        self.bridge.listener.bridge_data(PEER, list(PEER_IDENTITY))

        peer_interface = self.interface.spawned_interfaces[PEER_IDENTITY.hex()[:16]]
        self.assertIn(peer_interface, RNS.Transport.interfaces)
        peer_interface.process_outgoing(b"reticulum packet")
        sent = self.bridge.named("notifyTx")
        self.assertEqual(len(sent), 1)
        self.assertEqual(sent[0][1], PEER)
        self.assertEqual(sent[0][2][5:], b"reticulum packet")

        self.bridge.listener.bridge_data(PEER, sent[0][2])
        self.owner.inbound.assert_called_once_with(b"reticulum packet", peer_interface)

    def test_central_connection_creates_a_peer_that_sends_fragments_as_writes(self):
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)

        peer_interface = self.interface.spawned_interfaces[PEER_IDENTITY.hex()[:16]]
        peer_interface.process_outgoing(b"reticulum packet")
        sent = self.bridge.named("writeRx")
        self.assertEqual(len(sent), 1)
        self.assertEqual(sent[0][2][5:], b"reticulum packet")

    def test_silent_central_peer_is_disconnected_so_it_can_be_reconnected(self):
        now = [1000.0]
        self.interface.driver._clock = lambda: now[0]
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        peer_interface = self.interface.spawned_interfaces[PEER_IDENTITY.hex()[:16]]

        peer_interface.process_outgoing(b"first packet")
        peer_interface.process_outgoing(b"second packet")
        now[0] += ble.REPLY_TIMEOUT
        peer_interface.process_outgoing(b"third packet")

        self.assertEqual(len(self.bridge.named("writeRx")), 2)
        self.assertEqual(self.bridge.named("disconnect"), [("disconnect", PEER)])
        self.assertEqual(self.interface.driver.connected_peers, [])
        self.assertNotIn(PEER, self.interface.peers)

    def test_peer_is_reconnected_after_bluetooth_is_turned_off_and_on(self):
        import RNS

        device = lambda: self.bridge.listener.bridge_discovered(PEER, None, -50)
        self.interface.driver.set_identity(LOCAL_IDENTITY)
        device()
        self.assertEqual(self.bridge.named("connect"), [("connect", PEER)])
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        first = self.interface.spawned_interfaces[PEER_IDENTITY.hex()[:16]]

        self.bridge.listener.bridge_adapter_state(False)
        self.assertEqual(self.interface.driver.connected_peers, [])
        self.assertNotIn(PEER, self.interface.address_to_identity)
        self.assertEqual(self.interface.connection_blacklist, {})

        self.bridge.listener.bridge_adapter_state(True)
        # The reference waits five seconds between attempts to one address.
        self.interface.discovered_peers[PEER].last_connection_attempt -= 6
        device()
        self.assertEqual(self.bridge.named("connect"), [("connect", PEER)] * 2)
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)

        self.assertEqual(self.interface.driver.connected_peers, [PEER])
        peer_interface = self.interface.spawned_interfaces[PEER_IDENTITY.hex()[:16]]
        self.assertIs(peer_interface, first)
        self.assertTrue(peer_interface.online)
        self.assertIn(peer_interface, RNS.Transport.interfaces)
        peer_interface.process_outgoing(b"after the restart")
        self.assertEqual(self.bridge.named("writeRx")[-1][2][5:], b"after the restart")

    def test_detach_stops_the_bridge(self):
        self.bridge.listener.bridge_connected(PEER, "central", list(PEER_IDENTITY), 512)
        self.interface.detach()
        self.assertEqual(self.bridge.named("stop"), [("stop",)])
        self.assertEqual(self.interface.driver.connected_peers, [])
        self.assertFalse(self.interface.online)


if __name__ == "__main__":
    unittest.main()
