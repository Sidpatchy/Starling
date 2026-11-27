"""iOS driver for ble-reticulum, backed by the native CoreBluetooth bridge."""

import base64
import json
import threading
import time

from ble_reticulum.bluetooth_driver import BLEDevice, BLEDriverInterface, DriverState

INTERFACE_TYPE = "StarlingBLEPeerInterface"
INTERFACE_NAME = "Starling BLE Peers"
ROLE_CENTRAL = "central"
ROLE_PERIPHERAL = "peripheral"
UNKNOWN_LOCAL_ADDRESS = "00:00:00:00:00:00"
POWER_MODES = ("aggressive", "balanced", "saver")
IDENTITY_LENGTH = 16
MINIMUM_PAYLOAD = 20
REPLY_TIMEOUT = 30.0
REPLY_TIMEOUT_MAX = 480.0
UNANSWERED_SENDS = 3
MAX_TRACKED_REPLY_TIMEOUTS = 64

_ios = None
_ios_load_error = None
_interface_class = None
_peer_listener = None


def _native():
    """Load the embedded-only module on demand so desktop imports still work."""
    global _ios, _ios_load_error
    if _ios is None:
        try:
            import _starling_ble_ios as module
        except ImportError as error:
            _ios_load_error = error
            raise RuntimeError("The iOS Bluetooth peer bridge module is not available") from error
        _ios = module
    return _ios


def has_bridge():
    try:
        _native()
        return True
    except RuntimeError:
        return False


def set_peer_listener(listener):
    global _peer_listener
    _peer_listener = listener
    return True


def _notify_peer_linked():
    if _peer_listener is not None:
        try:
            _peer_listener()
        except Exception:
            pass


def _config_bool(value, default=True):
    if value is None:
        return default
    if isinstance(value, str):
        return value.strip().lower() in ("yes", "true", "1")
    return bool(value)


class IOSBLEDriver(BLEDriverInterface):
    """ble-reticulum driver; native events are drained on a stoppable worker."""

    def __init__(self, discovery_interval=5.0, connection_timeout=30.0, min_rssi=-85,
                 service_discovery_delay=0.0, max_peers=7, adapter_index=0, **_unused):
        self._native_module = _native()
        self.discovery_interval = float(discovery_interval)
        self.connection_timeout = float(connection_timeout)
        self.min_rssi = int(min_rssi)
        self.service_discovery_delay = float(service_discovery_delay)
        self.max_peers = int(max_peers)
        self.power_mode = "balanced"
        self.service_uuid = self.rx_char_uuid = self.tx_char_uuid = self.identity_char_uuid = None
        self._state = DriverState.IDLE
        self._running = False
        self._scanning = False
        self._advertising = False
        self._adapter_on = True
        self._local_identity = None
        self._peers = {}
        self._peers_lock = threading.RLock()
        self._notify_callbacks = {}
        self._connecting_peers = set()
        self._connecting_lock = threading.Lock()
        self._reply_timeouts = {}
        self._clock = time.monotonic
        self._worker = None
        self._stop_event = threading.Event()

    def start(self, service_uuid, rx_char_uuid, tx_char_uuid, identity_char_uuid):
        self.service_uuid = str(service_uuid).lower()
        self.rx_char_uuid = str(rx_char_uuid).lower()
        self.tx_char_uuid = str(tx_char_uuid).lower()
        self.identity_char_uuid = str(identity_char_uuid).lower()
        self._running = True
        self._adapter_on = True
        self._stop_event.clear()
        config = {
            "service_uuid": self.service_uuid, "rx_uuid": self.rx_char_uuid,
            "tx_uuid": self.tx_char_uuid, "identity_uuid": self.identity_char_uuid,
            "identity": base64.b64encode(self._local_identity).decode("ascii") if self._local_identity else "",
            "scan_timeout": self.discovery_interval, "max_peers": self.max_peers,
            "connection_timeout": self.connection_timeout,
        }
        try:
            self._native_module.start(json.dumps(config))
        except Exception as error:
            self._running = False
            raise RuntimeError(str(error)) from error
        self._worker = threading.Thread(target=self._poll_events, name="StarlingBLEEvents", daemon=True)
        self._worker.start()

    def stop(self):
        if not self._running:
            return
        self._running = False
        self._scanning = self._advertising = False
        self._stop_event.set()
        try:
            self._native_module.stop()
        finally:
            worker = self._worker
            if worker is not None and worker is not threading.current_thread():
                worker.join()
            self._worker = None
            with self._peers_lock:
                addresses = list(self._peers)
                self._peers.clear()
                self._notify_callbacks.clear()
            with self._connecting_lock:
                self._connecting_peers.clear()
            self._state = DriverState.IDLE
            for address in addresses:
                self._emit(self.on_device_disconnected, address)

    def set_identity(self, identity_bytes):
        if not isinstance(identity_bytes, bytes):
            raise TypeError("identity_bytes must be bytes")
        if len(identity_bytes) != IDENTITY_LENGTH:
            raise ValueError("identity_bytes must be 16 bytes")
        if self._running:
            self._native_module.set_identity(identity_bytes)
        self._local_identity = identity_bytes

    @property
    def state(self): return self._state

    @property
    def adapter_on(self): return self._adapter_on

    @property
    def connected_peers(self):
        with self._peers_lock:
            return list(self._peers)

    def _refresh_state(self):
        self._state = (DriverState.IDLE if not self._adapter_on else
                       DriverState.SCANNING if self._scanning else
                       DriverState.ADVERTISING if self._advertising else DriverState.IDLE)

    def start_scanning(self):
        self._require_running()
        self._native_module.start_scanning()
        self._scanning = True
        self._refresh_state()

    def stop_scanning(self):
        if self._running: self._native_module.stop_scanning()
        self._scanning = False
        self._refresh_state()

    def start_advertising(self, device_name, identity):
        self._require_running()
        if identity is not None and identity != self._local_identity:
            self.set_identity(identity)
        if self._local_identity is None:
            raise RuntimeError("Set the local identity before advertising")
        self._native_module.start_advertising()
        self._advertising = True
        self._refresh_state()

    def stop_advertising(self):
        if self._running: self._native_module.stop_advertising()
        self._advertising = False
        self._refresh_state()

    def connect(self, address):
        if not self._running or self._local_identity is None:
            return
        with self._peers_lock:
            if address in self._peers:
                return
            connected = len(self._peers)
        with self._connecting_lock:
            if address in self._connecting_peers or connected + len(self._connecting_peers) >= self.max_peers:
                return
            self._connecting_peers.add(address)
        try:
            self._native_module.connect(str(address))
        except Exception as error:
            with self._connecting_lock:
                self._connecting_peers.discard(address)
            self._emit(self.on_error, "error", "Connection failed to " + str(address) + ": " + str(error), None)

    def disconnect(self, address):
        with self._peers_lock:
            peer = self._peers.get(address)
            # The peripheral role is a remote-initiated CoreBluetooth link;
            # iOS cannot terminate it, so keep reporting it as connected.
            if peer is not None and peer["role"] == ROLE_PERIPHERAL:
                return
            peer = self._peers.pop(address, None)
            self._notify_callbacks.pop(address, None)
        if peer is not None:
            self._native_module.disconnect(str(address))
            self._emit(self.on_device_disconnected, address)

    def send(self, address, data):
        role = self.get_peer_role(address)
        if role is None:
            raise RuntimeError("Not connected to " + str(address))
        payload = bytes(data)
        if role == ROLE_CENTRAL:
            silent_for = self._note_send(address)
            if silent_for is not None:
                self._drop_silent_peer(address, silent_for)
                raise RuntimeError("Bluetooth peer " + str(address) + " stopped answering")
            operation = self._native_module.write
        else:
            operation = self._native_module.notify
        try:
            operation(str(address), payload)
        except Exception as error:
            raise RuntimeError("Bluetooth send to " + str(address) + " failed: " + str(error)) from error

    def read_characteristic(self, address, char_uuid):
        with self._peers_lock:
            peer = self._peers.get(address)
        if peer is None:
            raise RuntimeError("Not connected to " + str(address))
        if str(char_uuid).lower() == self.identity_char_uuid and peer["identity"] is not None:
            return peer["identity"]
        raise NotImplementedError("Only the peer identity characteristic can be read")

    def write_characteristic(self, address, char_uuid, data):
        if str(char_uuid).lower() != self.rx_char_uuid or self.get_peer_role(address) != ROLE_CENTRAL:
            raise NotImplementedError("Only the peer RX characteristic can be written")
        self.send(address, data)

    def start_notify(self, address, char_uuid, callback):
        if str(char_uuid).lower() != self.tx_char_uuid:
            raise NotImplementedError("Only the peer TX characteristic sends notifications")
        with self._peers_lock:
            if address not in self._peers:
                raise RuntimeError("Not connected to " + str(address))
            self._notify_callbacks[address] = callback

    def get_local_address(self): return UNKNOWN_LOCAL_ADDRESS

    def get_peer_role(self, address):
        with self._peers_lock:
            peer = self._peers.get(address)
        return peer["role"] if peer else None

    def get_peer_mtu(self, address):
        with self._peers_lock:
            peer = self._peers.get(address)
        return peer["mtu"] if peer else None

    def set_service_discovery_delay(self, seconds): self.service_discovery_delay = float(seconds)

    def set_power_mode(self, mode):
        if mode not in POWER_MODES:
            raise ValueError("Invalid power mode: " + str(mode))
        self.power_mode = mode

    def _poll_events(self):
        while not self._stop_event.wait(0.05):
            try:
                events = self._native_module.drain_events()
                for event in events:
                    if self._stop_event.is_set():
                        break
                    self._handle_event(event)
            except Exception as error:
                if self._running:
                    self._emit(self.on_error, "error", "iOS Bluetooth event polling failed: " + str(error), None)
                    self._stop_event.wait(0.5)

    def _handle_event(self, event):
        kind = event.get("kind")
        address = str(event.get("address", ""))
        if kind == "discovered":
            if not self._running: return
            rssi = int(event.get("rssi", -127))
            if rssi < self.min_rssi: return
            name = event.get("name")
            label = str(name) if name else "BLE-" + address[-8:]
            self._emit(self.on_device_discovered, BLEDevice(address=address, name=label, rssi=rssi, service_uuids=[self.service_uuid]))
        elif kind == "identity_read":
            identity = base64.b64decode(event.get("identity", ""))
            check = getattr(self, "on_duplicate_identity_detected", None)
            try: duplicate = bool(check(address, identity)) if check else False
            except Exception: duplicate = False
            if duplicate:
                with self._connecting_lock: self._connecting_peers.discard(address)
                # Central-role links are locally initiated, so CoreBluetooth
                # can cancel them while still in the identity handshake.
                try:
                    self._native_module.disconnect(address)
                except Exception as error:
                    self._emit(self.on_error, "warning", "Could not cancel duplicate-identity connection to " + address + ": " + str(error), None)
                self._emit(self.on_error, "info", "Duplicate identity rejected for " + address + " (MAC rotation)", None)
        elif kind == "connected":
            identity = base64.b64decode(event["identity"]) if event.get("identity") else None
            role = str(event.get("role", ROLE_CENTRAL))
            mtu = max(int(event.get("payload_size", MINIMUM_PAYLOAD)), MINIMUM_PAYLOAD)
            with self._peers_lock:
                self._peers[address] = {"role": role, "identity": identity if role == ROLE_CENTRAL else None,
                    "mtu": mtu, "unanswered_since": None, "unanswered": 0}
            with self._connecting_lock: self._connecting_peers.discard(address)
            self._emit(self.on_device_connected, address, identity if role == ROLE_CENTRAL else None)
            self._emit(self.on_mtu_negotiated, address, mtu)
            if role == ROLE_CENTRAL and identity is not None: _notify_peer_linked()
        elif kind == "connect_failed":
            with self._connecting_lock: self._connecting_peers.discard(address)
            if event.get("timed_out"):
                self._emit(self.on_error, "warning", "Connection timeout to " + address, None)
            else:
                self._emit(self.on_error, "error", "Connection failed to " + address + ": " + str(event.get("message", "")), None)
        elif kind == "disconnected":
            with self._peers_lock:
                peer = self._peers.pop(address, None)
                self._notify_callbacks.pop(address, None)
            if peer is not None and self._running: self._emit(self.on_device_disconnected, address)
        elif kind == "mtu_changed":
            mtu = max(int(event.get("payload_size", MINIMUM_PAYLOAD)), MINIMUM_PAYLOAD)
            with self._peers_lock:
                peer = self._peers.get(address)
                if peer is None or peer["mtu"] == mtu: return
                peer["mtu"] = mtu
            if self._running: self._emit(self.on_mtu_negotiated, address, mtu)
        elif kind == "data":
            if not self._running: return
            payload = base64.b64decode(event.get("data", ""))
            with self._peers_lock:
                peer = self._peers.get(address)
                if peer is None: return
                peer["unanswered_since"] = None
                peer["unanswered"] = 0
                self._reply_timeouts.pop(self._liveness_key(address, peer), None)
                listener = self._notify_callbacks.get(address)
            self._emit(self.on_data_received, address, payload)
            if listener is not None: self._emit(listener, payload)
        elif kind == "error":
            if self._running: self._emit(self.on_error, str(event.get("severity", "error")), str(event.get("message", "")), None)
        elif kind == "adapter_state":
            self._adapter_state(event.get("enabled", False))

    def _adapter_state(self, enabled):
        if not self._running: return
        enabled = bool(enabled)
        if enabled == self._adapter_on: return
        self._adapter_on = enabled
        self._refresh_state()
        if enabled:
            self._emit(self.on_error, "info", "Bluetooth is back on; looking for peers again", None)
            return
        with self._peers_lock:
            addresses = list(self._peers)
            self._peers.clear()
            self._notify_callbacks.clear()
        with self._connecting_lock: self._connecting_peers.clear()
        self._emit(self.on_error, "warning", "Bluetooth is off; peers will connect when it is turned on", None)
        for address in addresses: self._emit(self.on_device_disconnected, address)

    @staticmethod
    def _liveness_key(address, peer): return peer["identity"] if peer["identity"] is not None else address

    def _note_send(self, address):
        now = self._clock()
        with self._peers_lock:
            peer = self._peers.get(address)
            if peer is None: return None
            if peer["unanswered_since"] is None:
                peer["unanswered_since"], peer["unanswered"] = now, 1
                return None
            peer["unanswered"] += 1
            silent_for = now - peer["unanswered_since"]
            key = self._liveness_key(address, peer)
            timeout = self._reply_timeouts.get(key, REPLY_TIMEOUT)
            if peer["unanswered"] < UNANSWERED_SENDS or silent_for < timeout: return None
            if len(self._reply_timeouts) >= MAX_TRACKED_REPLY_TIMEOUTS: self._reply_timeouts.clear()
            self._reply_timeouts[key] = min(timeout * 2, REPLY_TIMEOUT_MAX)
            return silent_for

    def _drop_silent_peer(self, address, silent_for):
        self._emit(self.on_error, "warning", "No data from " + str(address) + " for " + str(int(silent_for)) + "s while sending; reconnecting", None)
        self.disconnect(address)

    def _require_running(self):
        if not self._running: raise RuntimeError("The Bluetooth peer driver is not running")

    def _emit(self, callback, *arguments):
        if callback is None: return
        try: callback(*arguments)
        except Exception as error:
            handler = self.on_error
            if callback is not handler and handler is not None:
                try: handler("warning", "Bluetooth peer callback failed: " + str(error), None)
                except Exception: pass


def load_interface_class():
    global _interface_class
    if _interface_class is None:
        from ble_reticulum.BLEInterface import BLEInterface
        from RNS.Interfaces.Interface import Interface

        class StarlingBLEInterface(BLEInterface):
            driver_class = IOSBLEDriver

            def __init__(self, owner, configuration):
                super().__init__(owner, configuration)
                config = Interface.get_config_obj(configuration)
                self.enable_peripheral = _config_bool(config.get("enable_peripheral"), True)

            def _spawn_peer_interface(self, address, name, peer_identity, *args, **kwargs):
                is_new = self._compute_identity_hash(peer_identity) not in self.spawned_interfaces
                peer_interface = super()._spawn_peer_interface(address, name, peer_identity, *args, **kwargs)
                if is_new and peer_interface is not None: _notify_peer_linked()
                return peer_interface

        _interface_class = StarlingBLEInterface
    return _interface_class


def peer_interfaces():
    import RNS
    result = []
    for interface in list(RNS.Transport.interfaces):
        if _interface_class is not None and isinstance(interface, _interface_class):
            peers = getattr(interface, "spawned_interfaces", None) or {}
            result.extend(peer for peer in list(peers.values()) if getattr(peer, "online", False))
    return result


def interface_status():
    try:
        import RNS
        for interface in RNS.Transport.interfaces:
            if interface.name == INTERFACE_NAME:
                peers = getattr(interface, "spawned_interfaces", None) or {}
                bluetooth_on = bool(getattr(getattr(interface, "driver", None), "adapter_on", True))
                connected = sum(1 for peer in peers.values() if getattr(peer, "online", False))
                return {"enabled": True, "online": bluetooth_on and bool(getattr(interface, "online", False)),
                        "peer_count": connected if bluetooth_on else 0, "bluetooth_on": bluetooth_on}
    except Exception:
        pass
    return {"enabled": True, "online": False, "peer_count": 0, "bluetooth_on": True}
