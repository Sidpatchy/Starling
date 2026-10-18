"""In-process command adapter for the embedded iOS Python runtime.

The command and event lines match the desktop bridge so both platforms share
the existing Reticulum, RRC, and LXMF protocol implementations.
"""

import json

from starling_bridge_protocol import (
    decode as _decode,
    encode as _encode,
    dispatch_lxmf,
    dispatch_rrc,
    parse_start_request,
    status_line,
)


_rns = None
_rrc = None
_lxmf = None
_last_self_event = None


def _prepare_rnode_interfaces(radios):
    """Route BLE RNodes through the native CoreBluetooth serial proxy."""
    prepared = []
    for radio in radios:
        interface = dict(radio)
        if str(interface.get("port", "")).lower().startswith("ble://"):
            interface["runtime_port"] = "tcp://127.0.0.1"
        prepared.append(interface)
    return prepared


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
    mode, rpc_key_hex, peers, radios = parse_start_request(start, platform="iOS")
    radios = _prepare_rnode_interfaces(radios)

    import starling_lxmf
    import starling_rns
    import starling_rrc

    _rns = starling_rns
    _rrc = starling_rrc
    _lxmf = starling_lxmf
    try:
        json.loads(_rns.start_node(data_dir, mode, peers, rpc_key_hex, radios, allow_reconfigure=True))

        lxmf_error = None
        try:
            _lxmf.start(data_dir)
        except Exception as error:
            lxmf_error = str(error) or "Could not start LXMF."

        _last_self_event = None
        ready = status_line(_rns).replace("STATUS\t", "READY\t", 1)
        response = [ready]
        if lxmf_error:
            response.append("EVENT\tLXMF_ERROR\t{}\t".format(_encode(lxmf_error)))
        events = _emit_events(include_current=True)
        if events:
            response.append(events)
        return "\n".join(response)
    except Exception as error:
        # Native startup can partially initialize Reticulum before failing.
        # Release every imported component, but preserve the startup error as
        # the response so cleanup problems do not hide the actionable cause.
        for module, method in (
            (starling_rrc, "disconnect"),
            (starling_lxmf, "stop"),
            (starling_rns, "disconnect_node"),
        ):
            cleanup = getattr(module, method, None)
            if cleanup is None:
                continue
            try:
                cleanup()
            except Exception:
                pass
        _rns = _rrc = _lxmf = None
        _last_self_event = None
        raise error


def _stop():
    global _rns, _rrc, _lxmf, _last_self_event
    errors = []
    try:
        for label, stop in (
            ("RRC", _rrc.disconnect if _rrc is not None else None),
            ("LXMF", _lxmf.stop if _lxmf is not None else None),
            ("Reticulum", _rns.disconnect_node if _rns is not None else None),
        ):
            if stop is None:
                continue
            try:
                stop()
            except Exception as error:
                errors.append("{} shutdown failed: {}".format(
                    label, str(error) or error.__class__.__name__))
    finally:
        _rns = _rrc = _lxmf = None
        _last_self_event = None
    response = ["EVENT\tERROR\t{}\t".format(_encode(message)) for message in errors]
    response.append("STOPPED")
    return "\n".join(response)


def invoke(command):
    """Run one bridge command and return status/event lines for Kotlin."""
    global _rns, _rrc, _lxmf
    fields = command.split("\t")
    action = fields[0]

    try:
        if action == "IDENTITY" and len(fields) == 3:
            from starling_identity import invoke as invoke_identity

            return invoke_identity(_decode(fields[1]), _decode(fields[2]))
        if action == "IOS_START":
            return _start(fields)
        if action == "STATUS":
            if _rns is None:
                return "STATUS\tSTOPPED"
            return status_line(_rns)
        if action == "POLL":
            return _emit_events()
        if action == "STOP":
            return _stop()

        if _rrc is None or _lxmf is None:
            raise RuntimeError("Start the Reticulum node before sending commands.")
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
