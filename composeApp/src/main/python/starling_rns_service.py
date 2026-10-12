"""Long-lived JSON-free line protocol wrapper for the desktop RNS bridge."""

import base64
import json
import os
import queue
import sys
import threading
import time


protocol_out = sys.stdout
commands = queue.Queue()


def decode(value):
    return base64.b64decode(value.encode("ascii")).decode("utf-8")


def encode(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def emit(line):
    protocol_out.write(line + "\n")
    protocol_out.flush()


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


def read_commands():
    for line in sys.stdin:
        commands.put(line.strip())
    commands.put("EOF")


def dispatch_rrc(command, rrc, lxmf=None):
    fields = command.split("\t")
    action = fields[0]
    if action == "RRC_CONNECT" and len(fields) == 3:
        display_name = decode(fields[2])
        if lxmf is not None:
            lxmf.set_display_name(display_name)
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
    else:
        raise ValueError("Invalid LXMF command")


def main():
    if len(sys.argv) != 2:
        emit("ERROR\t" + encode("Expected the Reticulum data directory."))
        return 2

    data_dir = sys.argv[1]
    os.makedirs(data_dir, exist_ok=True)
    log_file = open(os.path.join(data_dir, "starling-rns.log"), "a", encoding="utf-8", buffering=1)
    sys.stdout = log_file
    sys.stderr = log_file

    start = sys.stdin.readline().strip().split("\t")
    if len(start) < 6 or start[0] != "START":
        emit("ERROR\t" + encode("Invalid start request."))
        return 2

    mode = start[1]
    rpc_key_hex = decode(start[2])
    if start[3] != "4":
        emit("ERROR\t" + encode("Unsupported desktop bridge protocol version."))
        return 2
    try:
        peer_count = int(start[4])
        rnode_count = int(start[5])
    except ValueError:
        emit("ERROR\t" + encode("Invalid interface count."))
        return 2
    if peer_count < 0 or rnode_count < 0 or len(start) != 6 + peer_count * 4 + rnode_count * 6:
        emit("ERROR\t" + encode("Interface configuration data is incomplete."))
        return 2
    tcp_peers = []
    for index in range(peer_count):
        offset = 6 + index * 4
        tcp_peers.append({
            "host": decode(start[offset]),
            "port": int(start[offset + 1]),
            "ifac_network_name": decode(start[offset + 2]),
            "ifac_passphrase": decode(start[offset + 3]),
        })
    rnode_interfaces = []
    rnode_offset = 6 + peer_count * 4
    for index in range(rnode_count):
        offset = rnode_offset + index * 6
        rnode_interfaces.append({
            "port": decode(start[offset]),
            "frequency_hz": int(start[offset + 1]),
            "bandwidth_hz": int(start[offset + 2]),
            "tx_power_dbm": int(start[offset + 3]),
            "spreading_factor": int(start[offset + 4]),
            "coding_rate": int(start[offset + 5]),
        })

    try:
        import starling_rns
        import starling_rrc
        import starling_lxmf
        config_mode = "embedded" if mode == "EMBEDDED" else "existing_shared_instance"
        starling_rns.start_node(data_dir, config_mode, tcp_peers, rpc_key_hex, rnode_interfaces)
        lxmf_start_error = None
        try:
            starling_lxmf.start(data_dir)
        except Exception as error:
            lxmf_start_error = str(error) or "Could not start LXMF"
    except Exception as error:
        emit("ERROR\t" + encode(str(error) or error.__class__.__name__))
        return 1

    emit(status_line(starling_rns).replace("STATUS\t", "READY\t", 1))
    if lxmf_start_error is not None:
        emit("EVENT\tLXMF_ERROR\t{}\t".format(encode(lxmf_start_error)))
    threading.Thread(target=read_commands, name="starling-command-reader", daemon=True).start()

    next_status = time.monotonic() + 2
    while True:
        try:
            command = commands.get(timeout=0.1)
        except queue.Empty:
            command = None

        if command in ("STOP", "EOF"):
            try:
                starling_rrc.disconnect()
                starling_lxmf.stop()
                starling_rns.stop_node()
            finally:
                emit("STOPPED")
            return 0
        if command and command != "STATUS":
            try:
                if command.startswith("LXMF_"):
                    dispatch_lxmf(command, starling_lxmf)
                else:
                    dispatch_rrc(command, starling_rrc, starling_lxmf)
            except Exception as error:
                emit("EVENT\tERROR\t{}\t".format(encode(str(error) or error.__class__.__name__)))

        try:
            event_lines = starling_rrc.poll_event_lines()
            if event_lines:
                for event_line in event_lines.splitlines():
                    emit("EVENT\t" + event_line)
            lxmf_lines = starling_lxmf.poll_event_lines()
            if lxmf_lines:
                for event_line in lxmf_lines.splitlines():
                    emit("EVENT\t" + event_line)
            if time.monotonic() >= next_status:
                emit(status_line(starling_rns))
                next_status = time.monotonic() + 2
        except Exception as error:
            emit("EVENT\tERROR\t{}\t".format(encode(str(error) or error.__class__.__name__)))


if __name__ == "__main__":
    raise SystemExit(main())
