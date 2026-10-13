#!/usr/bin/env python3
"""Host smoke test for the iOS RNS bridge and IFAC configuration path."""

import base64
import os
import select
import socket
import subprocess
import sys
import tempfile
import threading
import time

import starling_ios_runtime
from RNS.Cryptography import Provider


def b64(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def start_ifac_server(data_dir, port, network_name, passphrase):
    server_code = r"""
import os, sys, time
import RNS
from RNS.vendor.configobj import ConfigObj

data_dir, port, network_name, passphrase = sys.argv[1:]
os.makedirs(data_dir, exist_ok=True)
config = ConfigObj()
config.filename = os.path.join(data_dir, "config")
config["reticulum"] = {"enable_transport": "no", "share_instance": "no"}
config["interfaces"] = {
    "IFAC smoke server": {
        "type": "TCPServerInterface",
        "enabled": "yes",
        "outgoing": "yes",
        "listen_ip": "127.0.0.1",
        "listen_port": port,
        "networkname": network_name,
        "passphrase": passphrase,
    },
}
config.write()
node = RNS.Reticulum(configdir=data_dir, loglevel=RNS.LOG_NONE)
identity = RNS.Identity()
destination = RNS.Destination(identity, RNS.Destination.IN, RNS.Destination.SINGLE, "ifac", "smoke")
print("READY\t" + destination.hash.hex(), flush=True)
while True:
    destination.announce()
    time.sleep(0.5)
"""
    environment = os.environ.copy()
    process = subprocess.Popen(
        [sys.executable, "-S", "-c", server_code, data_dir, str(port), network_name, passphrase],
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    ready, _, _ = select.select([process.stdout], [], [], 15)
    if not ready:
        process.terminate()
        raise RuntimeError("The local IFAC Reticulum server did not start.")
    response = process.stdout.readline().strip().split("\t")
    if len(response) != 2 or response[0] != "READY":
        process.terminate()
        raise RuntimeError("The local IFAC Reticulum server returned an invalid status.")
    return process, response[1]


def verify_ifac_client(data_dir, port, network_name, passphrase, destination_hash, expect_path):
    client_code = r"""
import base64, sys, time
import RNS
import starling_ios_runtime

def b64(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")

data_dir, port, network_name, passphrase, destination_hash, expect_path = sys.argv[1:]
command = "\t".join([
    "IOS_START", b64(data_dir), "EMBEDDED", b64(""), "4", "1", "0",
    b64("127.0.0.1"), port, b64(network_name), b64(passphrase),
])
response = starling_ios_runtime.invoke(command)
if not response.startswith("READY\tRUNNING\t"):
    raise RuntimeError(response)
destination = bytes.fromhex(destination_hash)
deadline = time.monotonic() + (15 if expect_path == "yes" else 4)
has_path = False
while time.monotonic() < deadline:
    starling_ios_runtime.invoke("POLL")
    has_path = RNS.Transport.has_path(destination)
    if has_path and expect_path == "yes":
        break
    if expect_path == "yes":
        RNS.Transport.request_path(destination)
    time.sleep(0.25)
if has_path != (expect_path == "yes"):
    raise RuntimeError("IFAC path expectation failed; has_path={}".format(has_path))
starling_ios_runtime.invoke("STOP")
sys.__stdout__.write("IFAC_" + ("MATCHED" if has_path else "MISMATCH_REJECTED") + "\n")
"""
    environment = os.environ.copy()
    process = subprocess.Popen(
        [sys.executable, "-S", "-c", client_code, data_dir, str(port), network_name,
         passphrase, destination_hash, "yes" if expect_path else "no"],
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    stdout, stderr = process.communicate(timeout=25)
    if process.returncode != 0:
        raise AssertionError("IFAC client failed: {}\n{}".format(stdout, stderr))
    expected = "IFAC_MATCHED" if expect_path else "IFAC_MISMATCH_REJECTED"
    assert expected in stdout, stdout


def main():
    assert Provider.PROVIDER == Provider.PROVIDER_INTERNAL
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    listener.settimeout(0.25)
    accepted = threading.Event()
    clients = []
    stopping = threading.Event()

    def accept_peer():
        while not stopping.is_set():
            try:
                client, _ = listener.accept()
            except TimeoutError:
                continue
            clients.append(client)
            accepted.set()
            threading.Thread(target=drain, args=(client,), daemon=True).start()

    def drain(client):
        try:
            while client.recv(4096):
                pass
        except OSError:
            pass

    threading.Thread(target=accept_peer, name="ios-ifac-smoke", daemon=True).start()
    try:
        with tempfile.TemporaryDirectory(prefix="starling-ios-rns-") as data_dir:
            command = "\t".join([
                "IOS_START", b64(data_dir), "EMBEDDED", b64(""), "4", "1", "0",
                b64("127.0.0.1"), str(listener.getsockname()[1]),
                b64("starling-ios-ifac-test"), b64("ifac-test-passphrase"),
            ])
            response = starling_ios_runtime.invoke(command)
            assert response.startswith("READY\tRUNNING\t"), response
            assert accepted.wait(10), "RNS did not open its outbound TCP socket"

            config = open(data_dir + "/config", encoding="ascii").read()
            assert "networkname = starling-ios-ifac-test" in config, config
            assert "passphrase = ifac-test-passphrase" in config, config
            assert starling_ios_runtime.invoke("STATUS").startswith("STATUS\tRUNNING\t")
            assert starling_ios_runtime.invoke("STOP") == "STOPPED"

            reconnect_command = "\t".join([
                "IOS_START", b64(data_dir), "EMBEDDED", b64(""), "4", "1", "0",
                b64("127.0.0.1"), str(listener.getsockname()[1]),
                b64("starling-ios-ifac-updated"), b64("updated-ifac-passphrase"),
            ])
            reconnect = starling_ios_runtime.invoke(reconnect_command)
            assert reconnect.startswith("READY\tRUNNING\t"), reconnect
            deadline = time.monotonic() + 10
            while len(clients) < 2 and time.monotonic() < deadline:
                time.sleep(0.05)
            assert len(clients) >= 2, "RNS did not reconnect its IFAC-configured TCP interface"
            updated_config = open(data_dir + "/config", encoding="ascii").read()
            assert "networkname = starling-ios-ifac-updated" in updated_config, updated_config
            assert "passphrase = updated-ifac-passphrase" in updated_config, updated_config
            assert starling_ios_runtime.invoke("STOP") == "STOPPED"
    finally:
        stopping.set()
        listener.close()
    for client in clients:
        client.close()

    # Verify IFAC with a real RNS peer, not only a raw TCP listener.
    with tempfile.TemporaryDirectory(prefix="starling-ios-ifac-server-") as server_dir:
        verification_name = "starling-rns-ifac-verification"
        verification_passphrase = "rns-ifac-verification-passphrase"
        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        server_port = probe.getsockname()[1]
        probe.close()
        server, destination_hash = start_ifac_server(
            server_dir, server_port, verification_name, verification_passphrase,
        )
        try:
            with tempfile.TemporaryDirectory(prefix="starling-ios-ifac-client-") as client_dir:
                verify_ifac_client(
                    client_dir,
                    server_port,
                    verification_name,
                    verification_passphrase,
                    destination_hash,
                    expect_path=True,
                )
            with tempfile.TemporaryDirectory(prefix="starling-ios-ifac-mismatch-") as client_dir:
                verify_ifac_client(
                    client_dir,
                    server_port,
                    verification_name,
                    "wrong-ifac-passphrase",
                    destination_hash,
                    expect_path=False,
                )
        finally:
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=5)

    sys.__stdout__.write("Embedded iOS RNS/IFAC smoke test passed.\n")


if __name__ == "__main__":
    main()
