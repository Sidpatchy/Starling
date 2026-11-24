"""Android driver for the ble-reticulum v2.2 peer-to-peer BLE interface.

ble-reticulum owns the protocol logic: peer selection, the identity
handshake, fragmentation, and the per-peer Reticulum interfaces. This module
supplies only the platform driver it asks for, by forwarding each operation
to the Kotlin bridge that owns Android's scanner, advertiser, GATT server,
and GATT clients.
"""

import threading
import time

from ble_reticulum.bluetooth_driver import BLEDevice, BLEDriverInterface, DriverState

INTERFACE_TYPE = "StarlingBLEPeerInterface"
INTERFACE_NAME = "Starling BLE Peers"

ROLE_CENTRAL = "central"
ROLE_PERIPHERAL = "peripheral"

# Android does not disclose the adapter address to apps.
UNKNOWN_LOCAL_ADDRESS = "00:00:00:00:00:00"
POWER_MODES = ("aggressive", "balanced", "saver")
IDENTITY_LENGTH = 16
MINIMUM_PAYLOAD = 20

# Liveness of links this device opened. The protocol has no signal for a peer
# that has discarded its state for a link that is still up: it drops what it
# is sent and stays silent. So a link is treated as dead once at least
# UNANSWERED_SENDS fragments have been sent over REPLY_TIMEOUT seconds or more
# with nothing received in between. The link is then closed, and the next
# discovery pass reconnects and repeats the identity handshake.
#
# Only sending can trip this, so an idle link is never closed. REPLY_TIMEOUT
# matches the 30 seconds after which ble-reticulum itself calls a connection
# without data a zombie. A healthy peer may simply have nothing to say, so
# the timeout doubles each time a link to the same peer is closed this way,
# up to REPLY_TIMEOUT_MAX, and returns to REPLY_TIMEOUT when the peer is heard.
REPLY_TIMEOUT = 30.0
REPLY_TIMEOUT_MAX = 480.0
UNANSWERED_SENDS = 3
MAX_TRACKED_REPLY_TIMEOUTS = 64

_bridge = None
_interface_class = None


def set_bridge(bridge):
    """Register the Kotlin bridge used by drivers created after this call."""
    global _bridge
    _bridge = bridge
    return True


def clear_bridge():
    global _bridge
    _bridge = None
    return True


def has_bridge():
    return _bridge is not None


def _as_bytes(value):
    """Convert a Java byte[] (or any bytes-like value) into Python bytes."""
    if value is None:
        return None
    if isinstance(value, bytes):
        return value
    try:
        return bytes(value)
    except (TypeError, ValueError):
        return bytes((int(item) & 0xFF) for item in value)


def _config_bool(value, default=True):
    if value is None:
        return default
    if isinstance(value, str):
        return value.strip().lower() in ("yes", "true", "1")
    return bool(value)


class AndroidBLEDriver(BLEDriverInterface):
    """Implements the ble-reticulum driver contract on top of the Kotlin bridge.

    Roles follow the reference Linux driver: ``central`` means this device
    opened the connection and sends with GATT writes; ``peripheral`` means the
    peer connected to the local GATT server and is sent notifications.
    """

    def __init__(self, bridge=None, discovery_interval=5.0, connection_timeout=30.0, min_rssi=-85,
                 service_discovery_delay=0.0, max_peers=7, adapter_index=0, **_unused):
        self._bridge = bridge if bridge is not None else _bridge
        if self._bridge is None:
            raise RuntimeError("The Android Bluetooth peer bridge is not available")

        self.discovery_interval = float(discovery_interval)
        self.connection_timeout = float(connection_timeout)
        self.min_rssi = int(min_rssi)
        self.service_discovery_delay = float(service_discovery_delay)
        self.max_peers = int(max_peers)
        self.power_mode = "balanced"

        self.service_uuid = None
        self.rx_char_uuid = None
        self.tx_char_uuid = None
        self.identity_char_uuid = None

        self._state = DriverState.IDLE
        self._running = False
        self._scanning = False
        self._advertising = False
        # False while the phone's Bluetooth is turned off. The bridge keeps the
        # scan and advertising requests and resumes them when it is back on.
        self._adapter_on = True
        self._local_identity = None
        self._peers = {}
        self._peers_lock = threading.RLock()
        self._notify_callbacks = {}
        # ble-reticulum's peer selection reads these to skip in-flight attempts.
        self._connecting_peers = set()
        self._connecting_lock = threading.Lock()
        # Peer identity (or address) -> the reply timeout currently applied to it.
        self._reply_timeouts = {}
        self._clock = time.monotonic

    # --- Lifecycle ---

    def start(self, service_uuid, rx_char_uuid, tx_char_uuid, identity_char_uuid):
        self.service_uuid = str(service_uuid).lower()
        self.rx_char_uuid = str(rx_char_uuid).lower()
        self.tx_char_uuid = str(tx_char_uuid).lower()
        self.identity_char_uuid = str(identity_char_uuid).lower()
        self._running = True
        self._adapter_on = True
        error = self._bridge.start(
            self.service_uuid, self.rx_char_uuid, self.tx_char_uuid, self.identity_char_uuid,
            self.connection_timeout, self.max_peers, self,
        )
        if error:
            self._running = False
            raise RuntimeError(str(error))

    def stop(self):
        if not self._running:
            return
        self._running = False
        self._scanning = False
        self._advertising = False
        try:
            self._bridge.stop()
        finally:
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
        self._local_identity = identity_bytes
        self._bridge.setIdentity(identity_bytes)

    # --- State ---

    @property
    def state(self):
        return self._state

    @property
    def connected_peers(self):
        with self._peers_lock:
            return list(self._peers)

    def _refresh_state(self):
        if not self._adapter_on:
            self._state = DriverState.IDLE
        elif self._scanning:
            self._state = DriverState.SCANNING
        elif self._advertising:
            self._state = DriverState.ADVERTISING
        else:
            self._state = DriverState.IDLE

    # --- Scanning and advertising ---

    def start_scanning(self):
        self._require_running()
        error = self._bridge.startScanning(self.power_mode)
        if error:
            raise RuntimeError(str(error))
        self._scanning = True
        self._refresh_state()

    def stop_scanning(self):
        self._bridge.stopScanning()
        self._scanning = False
        self._refresh_state()

    def start_advertising(self, device_name, identity):
        # Android can only advertise the adapter's own name, and the service
        # UUID already fills the advertisement, so device_name is not sent.
        self._require_running()
        if identity is not None and identity != self._local_identity:
            self.set_identity(identity)
        if self._local_identity is None:
            raise RuntimeError("Set the local identity before advertising")
        error = self._bridge.startAdvertising()
        if error:
            raise RuntimeError(str(error))
        self._advertising = True
        self._refresh_state()

    def stop_advertising(self):
        self._bridge.stopAdvertising()
        self._advertising = False
        self._refresh_state()

    # --- Connections ---

    def connect(self, address):
        if not self._running:
            return
        # A central announces itself by writing its identity first. Until the
        # identity is known, leave the peer for a later discovery pass.
        if self._local_identity is None:
            return
        with self._peers_lock:
            if address in self._peers:
                return
            connected = len(self._peers)
        with self._connecting_lock:
            if address in self._connecting_peers:
                return
            if connected + len(self._connecting_peers) >= self.max_peers:
                return
            # Protocol address ordering: when this side has the higher address
            # it only advertises and lets the peer open the connection.
            if not self._bridge.shouldInitiate(address):
                return
            self._connecting_peers.add(address)
        error = self._bridge.connect(address)
        if error:
            with self._connecting_lock:
                self._connecting_peers.discard(address)
            self._emit(self.on_error, "error", "Connection failed to " + address + ": " + str(error), None)

    def disconnect(self, address):
        with self._peers_lock:
            peer = self._peers.pop(address, None)
            self._notify_callbacks.pop(address, None)
        if peer is None:
            return
        self._bridge.disconnect(address)
        self._emit(self.on_device_disconnected, address)

    def send(self, address, data):
        role = self.get_peer_role(address)
        if role is None:
            raise RuntimeError("Not connected to " + address)
        payload = bytes(data)
        if role == ROLE_CENTRAL:
            silent_for = self._note_send(address)
            if silent_for is not None:
                self._drop_silent_peer(address, silent_for)
                raise RuntimeError("Bluetooth peer " + address + " stopped answering")
            delivered = self._bridge.writeRx(address, payload)
        else:
            delivered = self._bridge.notifyTx(address, payload)
        if not delivered:
            raise RuntimeError("Bluetooth send to " + address + " was not confirmed")

    # --- GATT characteristic operations ---

    def read_characteristic(self, address, char_uuid):
        with self._peers_lock:
            peer = self._peers.get(address)
        if peer is None:
            raise RuntimeError("Not connected to " + address)
        if str(char_uuid).lower() == self.identity_char_uuid and peer["identity"] is not None:
            return peer["identity"]
        raise NotImplementedError("Only the peer identity characteristic can be read")

    def write_characteristic(self, address, char_uuid, data):
        if str(char_uuid).lower() != self.rx_char_uuid or self.get_peer_role(address) != ROLE_CENTRAL:
            raise NotImplementedError("Only the peer RX characteristic can be written")
        self.send(address, data)

    def start_notify(self, address, char_uuid, callback):
        # The bridge subscribes to TX while connecting; this adds a listener.
        if str(char_uuid).lower() != self.tx_char_uuid:
            raise NotImplementedError("Only the peer TX characteristic sends notifications")
        with self._peers_lock:
            if address not in self._peers:
                raise RuntimeError("Not connected to " + address)
            self._notify_callbacks[address] = callback

    # --- Queries and configuration ---

    def get_local_address(self):
        address = self._bridge.localAddress()
        return str(address) if address else UNKNOWN_LOCAL_ADDRESS

    def get_peer_role(self, address):
        with self._peers_lock:
            peer = self._peers.get(address)
        return peer["role"] if peer else None

    def get_peer_mtu(self, address):
        with self._peers_lock:
            peer = self._peers.get(address)
        return peer["mtu"] if peer else None

    def set_service_discovery_delay(self, seconds):
        self.service_discovery_delay = float(seconds)

    def set_power_mode(self, mode):
        if mode not in POWER_MODES:
            raise ValueError("Invalid power mode: " + str(mode))
        self.power_mode = mode

    # --- Events from the Kotlin bridge ---

    def bridge_discovered(self, address, name, rssi):
        if not self._running:
            return
        address = str(address)
        rssi = int(rssi)
        if rssi < self.min_rssi:
            return
        label = str(name) if name else "BLE-" + address[-8:]
        self._emit(self.on_device_discovered, BLEDevice(
            address=address, name=label, rssi=rssi, service_uuids=[self.service_uuid],
        ))

    def bridge_identity_read(self, address, identity):
        """Return False to abandon a central connection to an already-linked identity."""
        if not self._running:
            return False
        address = str(address)
        identity = _as_bytes(identity)
        check = getattr(self, "on_duplicate_identity_detected", None)
        if check is None:
            return True
        try:
            duplicate = bool(check(address, identity))
        except Exception:
            return True
        if duplicate:
            with self._connecting_lock:
                self._connecting_peers.discard(address)
            self._emit(self.on_error, "info", "Duplicate identity rejected for " + address + " (MAC rotation)", None)
            return False
        return True

    def bridge_connected(self, address, role, identity, mtu):
        if not self._running:
            return
        address = str(address)
        role = str(role)
        identity = _as_bytes(identity) if role == ROLE_CENTRAL else None
        mtu = max(int(mtu), MINIMUM_PAYLOAD)
        with self._peers_lock:
            self._peers[address] = {
                "role": role, "identity": identity, "mtu": mtu, "unanswered_since": None, "unanswered": 0,
            }
        with self._connecting_lock:
            self._connecting_peers.discard(address)
        self._emit(self.on_device_connected, address, identity)
        self._emit(self.on_mtu_negotiated, address, mtu)

    def bridge_connect_failed(self, address, message, timed_out):
        address = str(address)
        with self._connecting_lock:
            self._connecting_peers.discard(address)
        if not self._running:
            return
        if timed_out:
            self._emit(self.on_error, "warning", "Connection timeout to " + address, None)
        else:
            self._emit(self.on_error, "error", "Connection failed to " + address + ": " + str(message), None)

    def bridge_disconnected(self, address):
        address = str(address)
        with self._peers_lock:
            peer = self._peers.pop(address, None)
            self._notify_callbacks.pop(address, None)
        if peer is not None and self._running:
            self._emit(self.on_device_disconnected, address)

    def bridge_mtu_changed(self, address, mtu):
        address = str(address)
        mtu = max(int(mtu), MINIMUM_PAYLOAD)
        with self._peers_lock:
            peer = self._peers.get(address)
            if peer is None or peer["mtu"] == mtu:
                return
            peer["mtu"] = mtu
        if self._running:
            self._emit(self.on_mtu_negotiated, address, mtu)

    def bridge_data(self, address, data):
        if not self._running:
            return
        address = str(address)
        payload = _as_bytes(data)
        with self._peers_lock:
            peer = self._peers.get(address)
            if peer is None:
                return
            peer["unanswered_since"] = None
            peer["unanswered"] = 0
            self._reply_timeouts.pop(self._liveness_key(address, peer), None)
            listener = self._notify_callbacks.get(address)
        self._emit(self.on_data_received, address, payload)
        if listener is not None:
            self._emit(listener, payload)

    def bridge_error(self, severity, message):
        if self._running:
            self._emit(self.on_error, str(severity), str(message), None)

    def bridge_adapter_state(self, enabled):
        """The phone's Bluetooth was turned off, or is usable again.

        Turning Bluetooth off closes every link without a disconnect event,
        so they are all reported here, and attempts in flight are forgotten
        without counting as failures. Once Bluetooth is back the bridge has
        already restarted scanning and advertising; peers are then found and
        connected through the usual discovery events.
        """
        if not self._running:
            return
        enabled = bool(enabled)
        if enabled == self._adapter_on:
            return
        self._adapter_on = enabled
        self._refresh_state()
        if enabled:
            self._emit(self.on_error, "info", "Bluetooth is back on; looking for peers again", None)
            return
        with self._peers_lock:
            addresses = list(self._peers)
            self._peers.clear()
            self._notify_callbacks.clear()
        with self._connecting_lock:
            self._connecting_peers.clear()
        self._emit(self.on_error, "warning", "Bluetooth was turned off; peers will reconnect when it is back on", None)
        for address in addresses:
            self._emit(self.on_device_disconnected, address)

    # --- Liveness of central links ---

    @staticmethod
    def _liveness_key(address, peer):
        return peer["identity"] if peer["identity"] is not None else address

    def _note_send(self, address):
        """Count a fragment about to be sent. Returns how long the peer has
        been silent when the link should be closed, otherwise None."""
        now = self._clock()
        with self._peers_lock:
            peer = self._peers.get(address)
            if peer is None:
                return None
            if peer["unanswered_since"] is None:
                peer["unanswered_since"] = now
                peer["unanswered"] = 1
                return None
            peer["unanswered"] += 1
            silent_for = now - peer["unanswered_since"]
            key = self._liveness_key(address, peer)
            timeout = self._reply_timeouts.get(key, REPLY_TIMEOUT)
            if peer["unanswered"] < UNANSWERED_SENDS or silent_for < timeout:
                return None
            if len(self._reply_timeouts) >= MAX_TRACKED_REPLY_TIMEOUTS:
                self._reply_timeouts.clear()
            self._reply_timeouts[key] = min(timeout * 2, REPLY_TIMEOUT_MAX)
            return silent_for

    def _drop_silent_peer(self, address, silent_for):
        self._emit(
            self.on_error, "warning",
            "No data from " + address + " for " + str(int(silent_for)) + "s while sending; reconnecting", None,
        )
        self.disconnect(address)

    # --- Helpers ---

    def _require_running(self):
        if not self._running:
            raise RuntimeError("The Bluetooth peer driver is not running")

    def _emit(self, callback, *arguments):
        if callback is None:
            return
        try:
            callback(*arguments)
        except Exception as error:
            if callback is self.on_error:
                return
            handler = self.on_error
            if handler is not None:
                try:
                    handler("warning", "Bluetooth peer callback failed: " + str(error), None)
                except Exception:
                    pass


def load_interface_class():
    """Build the Reticulum interface class on first use; it needs RNS imported."""
    global _interface_class
    if _interface_class is None:
        from ble_reticulum.BLEInterface import BLEInterface
        from RNS.Interfaces.Interface import Interface

        class StarlingBLEInterface(BLEInterface):
            driver_class = AndroidBLEDriver

            def __init__(self, owner, configuration):
                super().__init__(owner, configuration)
                # The reference turns peripheral mode off when its BlueZ GATT
                # server is unusable. The Android bridge has its own server.
                config = Interface.get_config_obj(configuration)
                self.enable_peripheral = _config_bool(config.get("enable_peripheral"), True)

        _interface_class = StarlingBLEInterface
    return _interface_class


def interface_status():
    """Summarise the configured BLE peer interface for the node status report."""
    try:
        import RNS
        for interface in RNS.Transport.interfaces:
            if interface.name == INTERFACE_NAME:
                peers = getattr(interface, "spawned_interfaces", None) or {}
                return {
                    "enabled": True,
                    "online": bool(getattr(interface, "online", False)),
                    "peer_count": sum(1 for peer in peers.values() if getattr(peer, "online", False)),
                }
    except Exception:
        pass
    return {"enabled": True, "online": False, "peer_count": 0}
