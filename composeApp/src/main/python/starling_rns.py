"""Small platform bridge to the reference Reticulum implementation.

On Android, the node runs in Starling's dedicated service process. Keep node
configuration separate from the RRC and LXMF chat protocol adapters.
"""

import json
import os
import re
import signal
import sys
import types
from importlib.machinery import ModuleSpec

_reticulum = None
_mode = None
_tcp_peers = []
_rnode_interfaces = []
_rpc_key_hex = None
_config_dir = None


def construct_without_required_process_signals(factory):
    """Allow service-owned runtimes to initialize outside Python's main thread.

    RNS normally installs SIGINT/SIGTERM handlers. Android owns service
    shutdown and invokes Python from a worker thread, where CPython forbids
    signal registration. Desktop still installs the handlers normally.
    """
    original_signal = signal.signal

    def safe_signal(signum, handler):
        try:
            return original_signal(signum, handler)
        except ValueError:
            return None

    signal.signal = safe_signal
    try:
        return factory()
    finally:
        signal.signal = original_signal


def _write_config(config_dir, mode, tcp_peers, rnode_interfaces, rpc_key_hex, android_platform=False):
    os.makedirs(config_dir, exist_ok=True)
    config_path = os.path.join(config_dir, "config")

    from RNS.vendor.configobj import ConfigObj

    config = ConfigObj()
    config.filename = config_path
    config.encoding = "ascii"
    config["reticulum"] = {
        "enable_transport": "no",
        "share_instance": "yes" if mode == "existing_shared_instance" else "no",
    }

    if mode == "existing_shared_instance":
        if not rpc_key_hex or len(rpc_key_hex) < 32 or len(rpc_key_hex) % 2 != 0:
            raise ValueError("The existing shared instance RPC key must be at least 16 bytes of hexadecimal data")
        try:
            bytes.fromhex(rpc_key_hex)
        except ValueError:
            raise ValueError("The existing shared instance RPC key must contain only hexadecimal characters")
        config["reticulum"].update({
            "shared_instance_type": "tcp",
            "shared_instance_port": "37428",
            "instance_control_port": "37429",
            "rpc_key": rpc_key_hex,
        })

    if mode == "embedded":
        interfaces = {}
        for index, peer in enumerate(tcp_peers, start=1):
            interface = {
                "type": "TCPClientInterface",
                "enabled": "yes",
                "target_host": peer["host"],
                "target_port": str(peer["port"]),
            }
            if peer["ifac_network_name"]:
                interface["networkname"] = peer["ifac_network_name"]
                interface["passphrase"] = peer["ifac_passphrase"]
            interfaces["Starling TCP " + str(index)] = interface
        for index, radio in enumerate(rnode_interfaces, start=1):
            interface = {
                "type": "RNodeInterface",
                "enabled": "yes",
                "frequency": str(radio["frequency_hz"]),
                "bandwidth": str(radio["bandwidth_hz"]),
                "txpower": str(radio["tx_power_dbm"]),
                "spreadingfactor": str(radio["spreading_factor"]),
                "codingrate": str(radio["coding_rate"]),
            }
            if android_platform:
                # RNS's Android-specific RNode adapter expects explicit TCP fields.
                # The loopback bridge handles BLE; Wi-Fi RNodes connect directly.
                interface.update({
                    "port": "",
                    "force_tcp": "yes",
                    "tcp_host": radio["runtime_port"],
                    "allow_bluetooth": "no",
                })
            else:
                interface["port"] = radio.get("runtime_port", radio["port"])
            interfaces["Starling RNode " + str(index)] = interface
        config["interfaces"] = interfaces

    config.write()
    try:
        os.chmod(config_path, 0o600)
    except OSError:
        # The app's no-backup internal directory is private even where chmod is unavailable.
        pass


def start_node(config_dir, mode, tcp_peers="[]", rpc_key_hex="", rnode_interfaces="[]", allow_reconfigure=False):
    global _reticulum, _mode, _tcp_peers, _rnode_interfaces, _rpc_key_hex, _config_dir
    import RNS
    from starling_identity import load_identity

    load_identity(config_dir)

    if mode not in ("embedded", "existing_shared_instance"):
        raise ValueError("Unsupported Reticulum runtime mode")
    if isinstance(tcp_peers, str):
        tcp_peers = json.loads(tcp_peers)
    if not isinstance(tcp_peers, list):
        raise ValueError("TCP peers must be a list")
    normalized_peers = []
    for peer in tcp_peers:
        if not isinstance(peer, dict):
            raise ValueError("Invalid TCP peer configuration")
        host = str(peer.get("host", "")).strip()
        port = int(peer.get("port", 0))
        network_name = str(peer.get("ifac_network_name", ""))
        passphrase = str(peer.get("ifac_passphrase", ""))
        if not re.fullmatch(r"[A-Za-z0-9_.:-]+", host):
            raise ValueError("Invalid TCP host")
        if not 1 <= port <= 65535:
            raise ValueError("TCP port must be between 1 and 65535")
        for label, value in (("IFAC network name", network_name), ("IFAC passphrase", passphrase)):
            if len(value) > 128 or any(ord(character) < 32 or ord(character) > 126 for character in value):
                raise ValueError(label + " must be at most 128 printable ASCII characters on one line")
        if bool(network_name) != bool(passphrase):
            raise ValueError("For each peer, configure both IFAC network name and passphrase, or neither")
        normalized_peers.append({
            "host": host,
            "port": port,
            "ifac_network_name": network_name,
            "ifac_passphrase": passphrase,
        })
    if isinstance(rnode_interfaces, str):
        rnode_interfaces = json.loads(rnode_interfaces)
    if not isinstance(rnode_interfaces, list):
        raise ValueError("RNode interfaces must be a list")
    android_platform = RNS.vendor.platformutils.is_android()
    normalized_rnodes = []
    for radio in rnode_interfaces:
        if not isinstance(radio, dict):
            raise ValueError("Invalid RNode interface configuration")
        port = str(radio.get("port", "")).strip()
        if not port or any(ord(character) < 32 or ord(character) == 127 for character in port):
            raise ValueError("RNode connection must be non-empty and cannot contain control characters")
        runtime_port = str(radio.get("runtime_port", port)).strip()
        if android_platform:
            lowered_port = port.lower()
            if lowered_port.startswith("ble://"):
                runtime_port = "127.0.0.1"
            elif lowered_port.startswith("tcp://"):
                runtime_port = port[len("tcp://"):].strip()
                if not re.fullmatch(r"[A-Za-z0-9_.:-]+", runtime_port):
                    raise ValueError("Android TCP RNode hosts may contain only letters, numbers, dots, dashes, underscores, and colons")
            else:
                raise ValueError("Android RNodes must use ble:// or tcp:// connections")
        if not runtime_port or any(ord(character) < 32 or ord(character) == 127 for character in runtime_port):
            raise ValueError("RNode runtime connection must be non-empty and cannot contain control characters")
        frequency_hz = int(radio.get("frequency_hz", 0))
        bandwidth_hz = int(radio.get("bandwidth_hz", 0))
        tx_power_dbm = int(radio.get("tx_power_dbm", -1))
        spreading_factor = int(radio.get("spreading_factor", 0))
        coding_rate = int(radio.get("coding_rate", 0))
        if not 137_000_000 <= frequency_hz <= 3_000_000_000:
            raise ValueError("RNode frequency must be between 137 MHz and 3 GHz")
        if not 7_800 <= bandwidth_hz <= 1_625_000:
            raise ValueError("RNode bandwidth must be between 7.8 kHz and 1.625 MHz")
        if not 0 <= tx_power_dbm <= 37:
            raise ValueError("RNode transmit power must be between 0 and 37 dBm")
        if not 5 <= spreading_factor <= 12:
            raise ValueError("RNode spreading factor must be between 5 and 12")
        if not 5 <= coding_rate <= 8:
            raise ValueError("RNode coding rate must be between 5 and 8")
        normalized_rnodes.append({
            "port": port,
            "runtime_port": runtime_port,
            "frequency_hz": frequency_hz,
            "bandwidth_hz": bandwidth_hz,
            "tx_power_dbm": tx_power_dbm,
            "spreading_factor": spreading_factor,
            "coding_rate": coding_rate,
        })
    endpoints = [(peer["host"].lower(), peer["port"]) for peer in normalized_peers]
    if len(set(endpoints)) != len(endpoints):
        raise ValueError("Each TCP peer must use a unique host and port")
    rnode_ports = [radio["port"].lower() for radio in normalized_rnodes]
    if len(set(rnode_ports)) != len(rnode_ports):
        raise ValueError("Each RNode interface must use a unique connection")
    rnode_runtime_ports = [radio["runtime_port"].lower() for radio in normalized_rnodes]
    if len(set(rnode_runtime_ports)) != len(rnode_runtime_ports):
        raise ValueError("Each RNode interface must use a unique runtime connection")
    if mode == "embedded" and not normalized_peers and not normalized_rnodes:
        raise ValueError("Add a TCP peer or an RNode interface before starting the embedded node")
    if mode == "existing_shared_instance" and (normalized_peers or normalized_rnodes):
        raise ValueError("Interfaces can only be configured for Starling's embedded node")
    if mode == "existing_shared_instance":
        normalized_peers = []
        normalized_rnodes = []

    if _reticulum is not None:
        same_configuration = (
            _mode == mode
            and _tcp_peers == normalized_peers
            and _rnode_interfaces == normalized_rnodes
            and _rpc_key_hex == (rpc_key_hex or None)
        )
        if same_configuration and mode == "existing_shared_instance" and not _reticulum.is_connected_to_shared_instance:
            _reticulum._Reticulum__start_local_interface()
            if not _reticulum.is_connected_to_shared_instance:
                raise RuntimeError("Could not reconnect to the existing shared Reticulum instance.")
        elif not same_configuration:
            if not allow_reconfigure:
                raise RuntimeError("The running RNS node has a different configuration. Disconnect it before changing modes or peers.")
            if _mode != mode:
                raise RuntimeError("Changing between embedded and shared-instance mode requires restarting Starling on iOS.")
            # iOS embeds CPython in the app process, where finalizing/restarting
            # the interpreter is unsafe. Reuse its RNS singleton and replace
            # only Starling's interfaces after an explicit disconnect.
            _disconnect_configured_interfaces()
            if android_platform and normalized_rnodes:
                _install_android_tcp_rnode_compat()
            _write_config(config_dir, mode, normalized_peers, normalized_rnodes, rpc_key_hex, android_platform)
            if mode == "embedded":
                from RNS.vendor.configobj import ConfigObj

                _reticulum.config = ConfigObj(_reticulum.configpath)
                for name, interface_config in _reticulum.config.get("interfaces", {}).items():
                    _reticulum._synthesize_interface(interface_config, name, force_attach=True)
            elif not _reticulum.is_connected_to_shared_instance:
                _reticulum._Reticulum__start_local_interface()
                if not _reticulum.is_connected_to_shared_instance:
                    raise RuntimeError("Could not reconnect to the existing shared Reticulum instance.")
            _mode = mode
            _tcp_peers = normalized_peers
            _rnode_interfaces = normalized_rnodes
            _rpc_key_hex = rpc_key_hex or None
            _config_dir = config_dir
        return status()

    os.makedirs(config_dir, exist_ok=True)
    try:
        os.chmod(config_dir, 0o700)
    except OSError:
        pass
    os.umask(0o077)
    if android_platform and normalized_rnodes:
        _install_android_tcp_rnode_compat()
    _write_config(config_dir, mode, normalized_peers, normalized_rnodes, rpc_key_hex, android_platform)
    _reticulum = construct_without_required_process_signals(lambda: RNS.Reticulum(
        configdir=config_dir,
        loglevel=RNS.LOG_NOTICE,
        require_shared_instance=(mode == "existing_shared_instance"),
    ))
    _mode = mode
    _tcp_peers = normalized_peers
    _rnode_interfaces = normalized_rnodes
    _rpc_key_hex = rpc_key_hex or None
    _config_dir = config_dir
    return status()


def _disconnect_configured_interfaces():
    if _reticulum is None:
        return

    import RNS

    if _mode == "embedded":
        for interface in list(RNS.Transport.interfaces):
            if interface.name.startswith("Starling TCP ") or interface.name.startswith("Starling RNode "):
                _reticulum._detach_interface(interface.name, internal_forced=True)
    elif _mode == "existing_shared_instance":
        interface = _reticulum.shared_instance_interface
        if interface is not None:
            try:
                interface.detach()
            finally:
                RNS.Transport.remove_interface(interface)
                if interface in RNS.Transport.local_client_interfaces:
                    RNS.Transport.local_client_interfaces.remove(interface)
                _reticulum.shared_instance_interface = None
                _reticulum.is_connected_to_shared_instance = False
                RNS.Transport.shared_connection_disappeared()


def disconnect_node():
    """Detach Starling's interfaces but keep the in-process RNS core reusable."""
    _disconnect_configured_interfaces()
    global _tcp_peers, _rnode_interfaces
    _tcp_peers = []
    _rnode_interfaces = []
    return True


def status():
    if _reticulum is None:
        return json.dumps({"running": False})

    tcp_interfaces = []
    rnode_interfaces = []
    if _mode == "embedded":
        try:
            import RNS
            configured = {"Starling TCP " + str(index): peer for index, peer in enumerate(_tcp_peers, start=1)}
            configured_rnodes = {"Starling RNode " + str(index): radio for index, radio in enumerate(_rnode_interfaces, start=1)}
            for interface in RNS.Transport.interfaces:
                peer = configured.get(interface.name)
                if peer is not None:
                    tcp_interfaces.append({
                        "host": peer["host"],
                        "port": peer["port"],
                        "online": bool(getattr(interface, "online", False)),
                    })
                radio = configured_rnodes.get(interface.name)
                if radio is not None:
                    rnode_interfaces.append({
                        "port": radio["port"],
                        "online": bool(getattr(interface, "online", False)),
                        "frequency_hz": radio["frequency_hz"],
                        "bandwidth_hz": radio["bandwidth_hz"],
                        "tx_power_dbm": radio["tx_power_dbm"],
                        "spreading_factor": radio["spreading_factor"],
                        "coding_rate": radio["coding_rate"],
                    })
        except Exception:
            tcp_interfaces = [
                {"host": peer["host"], "port": peer["port"], "online": False}
                for peer in _tcp_peers
            ]
            rnode_interfaces = [
                {
                    "port": radio["port"], "online": False,
                    "frequency_hz": radio["frequency_hz"],
                    "bandwidth_hz": radio["bandwidth_hz"],
                    "tx_power_dbm": radio["tx_power_dbm"],
                    "spreading_factor": radio["spreading_factor"],
                    "coding_rate": radio["coding_rate"],
                }
                for radio in _rnode_interfaces
            ]

    return json.dumps({
        "running": True,
        "mode": _mode,
        "tcp_interfaces": tcp_interfaces,
        "rnode_interfaces": rnode_interfaces,
        "shared_instance_connected": bool(getattr(_reticulum, "is_connected_to_shared_instance", False)),
    })


def _install_android_tcp_rnode_compat():
    """Satisfy RNS's Android-only USB imports when using its TCP RNode path.

    Starling supports only TCP-backed RNodes on Android. The native USB and
    Bluetooth implementations require PyJNIus/usbserial4a, neither of which is
    used by this app. RNS currently checks for those modules before selecting
    its TCP mode, so provide import-only modules for that unused branch.
    """
    package = sys.modules.get("usbserial4a")
    if package is None:
        package = types.ModuleType("usbserial4a")
        package.__spec__ = ModuleSpec("usbserial4a", loader=None, is_package=True)
        package.__path__ = []
        sys.modules["usbserial4a"] = package

    serial_module = sys.modules.get("usbserial4a.serial4a")
    if serial_module is None:
        serial_module = types.ModuleType("usbserial4a.serial4a")
        serial_module.__spec__ = ModuleSpec("usbserial4a.serial4a", loader=None)
        sys.modules["usbserial4a.serial4a"] = serial_module
    package.serial4a = serial_module

    if "jnius" not in sys.modules:
        jnius_module = types.ModuleType("jnius")
        jnius_module.__spec__ = ModuleSpec("jnius", loader=None)
        sys.modules["jnius"] = jnius_module


def stop_node():
    global _reticulum, _mode, _tcp_peers, _rnode_interfaces, _rpc_key_hex, _config_dir
    if _reticulum is not None:
        import RNS
        RNS.Reticulum.exit_handler()
    _reticulum = None
    _mode = None
    _tcp_peers = []
    _rnode_interfaces = []
    _rpc_key_hex = None
    _config_dir = None
    return True
