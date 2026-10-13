"""In-process command adapter for the embedded iOS Python runtime.

The command and event lines match the desktop bridge so both platforms share
the existing Reticulum, RRC, and LXMF protocol implementations.
"""

import base64
import json


_rns = None
_rrc = None
_lxmf = None
_last_self_event = None


def _encode(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def _decode(value):
    return base64.b64decode(value.encode("ascii")).decode("utf-8")


def _emit_events(include_current=False):
    global _last_self_event
    result = []
    for module in (_rrc, _lxmf):
        if module is None:
            continue
        lines = module.poll_event_lines()
        if lines:
            result.extend("EVENT\t" + line for line in lines.splitlines() if line)

    if include_current:
        current_lines = (
            _rrc.current_connection_event_line() if _rrc is not None else None,
            _rrc.current_self_event_line() if _rrc is not None else None,
            _lxmf.current_local_address_event_line() if _lxmf is not None else None,
            _lxmf.current_identity_event_line() if _lxmf is not None else None,
        )
        for line in current_lines:
            if not line:
                continue
            if line.startswith("SELF\t"):
                if line == _last_self_event:
                    continue
                _last_self_event = line
            result.append("EVENT\t" + line)

    return "\n".join(result)


def _start(fields):
    global _rns, _rrc, _lxmf, _last_self_event
    if len(fields) < 7:
        raise ValueError("Incomplete iOS Reticulum start request.")

    data_dir = _decode(fields[1])
    start = ["START"] + fields[2:]
    if start[3] != "4":
        raise ValueError("Unsupported iOS bridge protocol version.")
    peer_count = int(start[4])
    radio_count = int(start[5])
    if peer_count < 0 or radio_count < 0 or len(start) != 6 + peer_count * 4 + radio_count * 6:
        raise ValueError("Interface configuration data is incomplete.")

    peers = []
    for index in range(peer_count):
        offset = 6 + index * 4
        peers.append({
            "host": _decode(start[offset]),
            "port": int(start[offset + 1]),
            "ifac_network_name": _decode(start[offset + 2]),
            "ifac_passphrase": _decode(start[offset + 3]),
        })

    radios = []
    radio_offset = 6 + peer_count * 4
    for index in range(radio_count):
        offset = radio_offset + index * 6
        radios.append({
            "port": _decode(start[offset]),
            "frequency_hz": int(start[offset + 1]),
            "bandwidth_hz": int(start[offset + 2]),
            "tx_power_dbm": int(start[offset + 3]),
            "spreading_factor": int(start[offset + 4]),
            "coding_rate": int(start[offset + 5]),
        })

    import starling_lxmf
    import starling_rns
    import starling_rrc

    _rns = starling_rns
    _rrc = starling_rrc
    _lxmf = starling_lxmf
    mode = "embedded" if start[1] == "EMBEDDED" else "existing_shared_instance"
    status = json.loads(_rns.start_node(data_dir, mode, peers, _decode(start[2]), radios, allow_reconfigure=True))

    lxmf_error = None
    try:
        _lxmf.start(data_dir)
    except Exception as error:
        lxmf_error = str(error) or "Could not start LXMF."

    _last_self_event = None
    from starling_rns_service import status_line
    ready = status_line(_rns).replace("STATUS\t", "READY\t", 1)
    response = [ready]
    if lxmf_error:
        response.append("EVENT\tLXMF_ERROR\t{}\t".format(_encode(lxmf_error)))
    events = _emit_events(include_current=True)
    if events:
        response.append(events)
    return "\n".join(response)


def _stop():
    global _rns, _rrc, _lxmf, _last_self_event
    try:
        if _rrc is not None:
            _rrc.disconnect()
        if _lxmf is not None:
            _lxmf.stop()
        if _rns is not None:
            _rns.disconnect_node()
    finally:
        _rns = _rrc = _lxmf = None
        _last_self_event = None
    return "STOPPED"


def invoke(command):
    """Run one bridge command and return status/event lines for Kotlin."""
    global _rns, _rrc, _lxmf
    fields = command.split("\t")
    action = fields[0]

    try:
        if action == "IOS_START":
            return _start(fields)
        if action == "STATUS":
            if _rns is None:
                return "STATUS\tSTOPPED"
            from starling_rns_service import status_line
            return status_line(_rns)
        if action == "POLL":
            return _emit_events()
        if action == "STOP":
            return _stop()

        if _rrc is None or _lxmf is None:
            raise RuntimeError("Start the Reticulum node before sending commands.")
        from starling_rns_service import dispatch_lxmf, dispatch_rrc
        if action.startswith("LXMF_"):
            dispatch_lxmf(command, _lxmf)
        else:
            dispatch_rrc(command, _rrc)
        return _emit_events()
    except Exception as error:
        message = str(error) or error.__class__.__name__
        encoded = _encode(message)
        if action == "IOS_START":
            return "ERROR\t" + encoded
        return "EVENT\tERROR\t{}\t".format(encoded)
