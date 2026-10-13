"""Platform-neutral commands and status fields for Starling's Python bridges."""

import base64
import json


def decode(value):
    return base64.b64decode(value.encode("ascii")).decode("utf-8")


def encode(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def parse_start_request(fields, platform="desktop"):
    """Return runtime mode, RPC key, TCP peers, and RNode interfaces for START."""
    if len(fields) < 6 or fields[0] != "START":
        raise ValueError("Invalid start request.")
    if fields[3] != "4":
        raise ValueError("Unsupported {} bridge protocol version.".format(platform))
    try:
        peer_count = int(fields[4])
        radio_count = int(fields[5])
    except ValueError as error:
        raise ValueError("Invalid interface count.") from error
    if peer_count < 0 or radio_count < 0 or len(fields) != 6 + peer_count * 4 + radio_count * 6:
        raise ValueError("Interface configuration data is incomplete.")

    peers = []
    for index in range(peer_count):
        offset = 6 + index * 4
        peers.append({
            "host": decode(fields[offset]),
            "port": int(fields[offset + 1]),
            "ifac_network_name": decode(fields[offset + 2]),
            "ifac_passphrase": decode(fields[offset + 3]),
        })

    radios = []
    radio_offset = 6 + peer_count * 4
    for index in range(radio_count):
        offset = radio_offset + index * 6
        radios.append({
            "port": decode(fields[offset]),
            "frequency_hz": int(fields[offset + 1]),
            "bandwidth_hz": int(fields[offset + 2]),
            "tx_power_dbm": int(fields[offset + 3]),
            "spreading_factor": int(fields[offset + 4]),
            "coding_rate": int(fields[offset + 5]),
        })

    mode = "embedded" if fields[1] == "EMBEDDED" else "existing_shared_instance"
    return mode, decode(fields[2]), peers, radios


def status_line(module):
    value = json.loads(module.status())
    if not value.get("running"):
        return "STATUS\tSTOPPED"
    peer_status = ";".join(
        "{},{},{}".format(encode(peer.get("host", "")), peer.get("port", 0), 1 if peer.get("online") else 0)
        for peer in value.get("tcp_interfaces", [])
    )
    rnode_status = ";".join(
        "{},{}".format(encode(radio.get("port", "")), 1 if radio.get("online") else 0)
        for radio in value.get("rnode_interfaces", [])
    )
    return "STATUS\tRUNNING\t{}\t{}\t{}\t{}\t{}".format(
        value.get("mode", "embedded"),
        encode(peer_status),
        len(value.get("tcp_interfaces", [])),
        1 if value.get("shared_instance_connected") else 0,
        encode(rnode_status),
    )


def dispatch_rrc(command, rrc):
    fields = command.split("\t")
    action = fields[0]
    if action == "RRC_CONNECT" and len(fields) == 3:
        display_name = decode(fields[2])
        rrc.connect(decode(fields[1]), display_name)
    elif action == "RRC_DISCONNECT":
        rrc.disconnect()
    elif action == "RRC_JOIN" and len(fields) == 2:
        rrc.join(decode(fields[1]))
    elif action == "RRC_PART" and len(fields) == 2:
        rrc.part(decode(fields[1]))
    elif action == "RRC_SEND" and len(fields) == 4:
        rrc.send(decode(fields[1]), decode(fields[2]), fields[3] == "1")
    else:
        raise ValueError("Invalid RRC command")


def dispatch_lxmf(command, lxmf):
    fields = command.split("\t")
    if fields[0] == "LXMF_SEND" and len(fields) == 3:
        lxmf.send_direct(decode(fields[1]), decode(fields[2]))
    elif fields[0] == "LXMF_SET_NAME" and len(fields) == 2:
        lxmf.set_display_name(decode(fields[1]))
    else:
        raise ValueError("Invalid LXMF command")
