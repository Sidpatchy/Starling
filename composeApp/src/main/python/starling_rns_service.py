"""Long-lived JSON-free line protocol wrapper for the desktop RNS bridge."""

import os
import queue
import sys
import threading
import time

from starling_bridge_protocol import dispatch_lxmf, dispatch_rrc, encode, parse_start_request, status_line

protocol_out = sys.stdout
commands = queue.Queue()


def emit(line):
    protocol_out.write(line + "\n")
    protocol_out.flush()


def read_commands():
    for line in sys.stdin:
        commands.put(line.rstrip("\r\n"))
    commands.put("EOF")


def main():
    if len(sys.argv) != 2:
        emit("ERROR\t" + encode("Expected the Reticulum data directory."))
        return 2

    data_dir = sys.argv[1]
    os.makedirs(data_dir, exist_ok=True)
    log_file = open(os.path.join(data_dir, "starling-rns.log"), "a", encoding="utf-8", buffering=1)
    sys.stdout = log_file
    sys.stderr = log_file

    start = sys.stdin.readline().rstrip("\r\n").split("\t")
    try:
        config_mode, rpc_key_hex, tcp_peers, rnode_interfaces = parse_start_request(start)
    except ValueError as error:
        emit("ERROR\t" + encode(str(error)))
        return 2

    starling_rns = starling_rrc = starling_lxmf = None
    try:
        import starling_rns
        import starling_rrc
        import starling_lxmf
        starling_rns.start_node(data_dir, config_mode, tcp_peers, rpc_key_hex, rnode_interfaces)
        lxmf_start_error = None
        try:
            starling_lxmf.start(data_dir)
        except Exception as error:
            lxmf_start_error = str(error) or "Could not start LXMF"
    except Exception as error:
        # RNS can allocate interfaces or worker threads before reporting a
        # startup error. Tear down every module that imported successfully so
        # a failed launch does not leave a half-running sidecar behind.
        for label, module, method in (
            ("RRC", starling_rrc, "disconnect"),
            ("LXMF", starling_lxmf, "stop"),
            ("Reticulum", starling_rns, "stop_node"),
        ):
            cleanup = getattr(module, method, None) if module is not None else None
            if cleanup is None:
                continue
            try:
                cleanup()
            except Exception as cleanup_error:
                log_file.write("{} startup cleanup failed: {}\n".format(
                    label, str(cleanup_error) or cleanup_error.__class__.__name__))
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
            # A failure to close the optional room or LXMF client must not leave
            # Reticulum running after the host has asked the sidecar to stop.
            for label, stop in (
                ("RRC", starling_rrc.disconnect),
                ("LXMF", starling_lxmf.stop),
                ("Reticulum", starling_rns.stop_node),
            ):
                try:
                    stop()
                except Exception as error:
                    emit("EVENT\tERROR\t{}\t".format(encode("{} shutdown failed: {}".format(
                        label, str(error) or error.__class__.__name__))))
            emit("STOPPED")
            return 0
        if command and command != "STATUS":
            try:
                if command.startswith("LXMF_"):
                    dispatch_lxmf(command, starling_lxmf)
                else:
                    dispatch_rrc(command, starling_rrc)
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
