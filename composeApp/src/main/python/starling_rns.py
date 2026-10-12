"""Small platform bridge to the reference Reticulum implementation.

On Android, the node runs in Starling's dedicated service process. Keep node
configuration separate from the RRC and LXMF chat protocol adapters.
"""

import json
import os
import re
import signal

_reticulum = None
_mode = None
_tcp_peers = []
_rpc_key_hex = None


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


def _write_config(config_dir, mode, tcp_peers, rpc_key_hex):
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
        config["interfaces"] = interfaces

    config.write()
    try:
        os.chmod(config_path, 0o600)
    except OSError:
        # The app's no-backup internal directory is private even where chmod is unavailable.
        pass


def start_node(config_dir, mode, tcp_peers="[]", rpc_key_hex=""):
    global _reticulum, _mode, _tcp_peers, _rpc_key_hex
    import RNS

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
    endpoints = [(peer["host"].lower(), peer["port"]) for peer in normalized_peers]
    if len(set(endpoints)) != len(endpoints):
        raise ValueError("Each TCP peer must use a unique host and port")
    if mode == "embedded" and not normalized_peers:
        raise ValueError("Add at least one TCP peer before starting the embedded node")
    if mode == "existing_shared_instance" and normalized_peers:
        raise ValueError("TCP peers are only supported in embedded mode")
    if mode == "existing_shared_instance":
        normalized_peers = []

    if _reticulum is not None:
        if (
            _mode != mode
            or _tcp_peers != normalized_peers
            or _rpc_key_hex != (rpc_key_hex or None)
        ):
            raise RuntimeError("The running RNS node has a different configuration. Disconnect it before changing modes or peers.")
        return status()

    os.makedirs(config_dir, exist_ok=True)
    try:
        os.chmod(config_dir, 0o700)
    except OSError:
        pass
    os.umask(0o077)
    _write_config(config_dir, mode, normalized_peers, rpc_key_hex)
    _reticulum = construct_without_required_process_signals(lambda: RNS.Reticulum(
        configdir=config_dir,
        loglevel=RNS.LOG_NOTICE,
        require_shared_instance=(mode == "existing_shared_instance"),
    ))
    _mode = mode
    _tcp_peers = normalized_peers
    _rpc_key_hex = rpc_key_hex or None
    return status()


def status():
    if _reticulum is None:
        return json.dumps({"running": False})

    tcp_interfaces = []
    if _mode == "embedded":
        try:
            import RNS
            configured = {"Starling TCP " + str(index): peer for index, peer in enumerate(_tcp_peers, start=1)}
            for interface in RNS.Transport.interfaces:
                peer = configured.get(interface.name)
                if peer is not None:
                    tcp_interfaces.append({
                        "host": peer["host"],
                        "port": peer["port"],
                        "online": bool(getattr(interface, "online", False)),
                    })
        except Exception:
            tcp_interfaces = [
                {"host": peer["host"], "port": peer["port"], "online": False}
                for peer in _tcp_peers
            ]

    return json.dumps({
        "running": True,
        "mode": _mode,
        "tcp_interfaces": tcp_interfaces,
        "shared_instance_connected": bool(getattr(_reticulum, "is_connected_to_shared_instance", False)),
    })


def stop_node():
    global _reticulum, _mode, _tcp_peers, _rpc_key_hex
    if _reticulum is not None:
        import RNS
        RNS.Reticulum.exit_handler()
    _reticulum = None
    _mode = None
    _tcp_peers = []
    _rpc_key_hex = None
    return True
