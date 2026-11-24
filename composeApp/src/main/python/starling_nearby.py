"""People heard through LXMF delivery announces, kept per delivery address.

Each person has at most one Bluetooth reading (heard over Starling's BLE peer
interface) and one network reading (heard over any other interface). Readings
expire separately, and only the most recently heard network-only people are
kept. This module has no Reticulum dependency so it can be tested on its own.
"""

import threading
import time

KIND_BLE = "ble"
KIND_TCP = "tcp"
KIND_RNODE = "rnode"

BLE_EXPIRY_SECONDS = 60 * 60
NETWORK_EXPIRY_SECONDS = 3 * 24 * 60 * 60
NETWORK_LIMIT = 50


class NearbyPeers:
    def __init__(self, clock=time.time):
        self._clock = clock
        self._lock = threading.Lock()
        self._entries = {}

    def heard(self, address, name, kind, hops):
        """Record one announce and return the merged entry for that address."""
        now = self._clock()
        with self._lock:
            entry = self._entries.get(address)
            if entry is None:
                entry = {
                    "address": address,
                    "name": "",
                    "ble_heard": 0.0,
                    "ble_hops": 0,
                    "network_heard": 0.0,
                    "network_hops": 0,
                    "network_kind": "",
                }
                self._entries[address] = entry
            if name:
                entry["name"] = name
            if kind == KIND_BLE:
                entry["ble_heard"] = now
                entry["ble_hops"] = int(hops)
            else:
                entry["network_heard"] = now
                entry["network_hops"] = int(hops)
                entry["network_kind"] = kind or ""
            self._prune(now)
            return dict(entry)

    def entries(self):
        """Current entries, most recently heard first."""
        with self._lock:
            self._prune(self._clock())
            result = [dict(entry) for entry in self._entries.values()]
        result.sort(key=lambda entry: max(entry["ble_heard"], entry["network_heard"]), reverse=True)
        return result

    def clear(self):
        with self._lock:
            self._entries.clear()

    def _prune(self, now):
        for address in list(self._entries):
            entry = self._entries[address]
            if entry["ble_heard"] and now - entry["ble_heard"] >= BLE_EXPIRY_SECONDS:
                entry["ble_heard"] = 0.0
                entry["ble_hops"] = 0
            if entry["network_heard"] and now - entry["network_heard"] >= NETWORK_EXPIRY_SECONDS:
                entry["network_heard"] = 0.0
                entry["network_hops"] = 0
                entry["network_kind"] = ""
            if not entry["ble_heard"] and not entry["network_heard"]:
                del self._entries[address]
        # Bluetooth is uncapped; the limit applies to people only heard on the network.
        network_only = [entry for entry in self._entries.values() if not entry["ble_heard"]]
        if len(network_only) > NETWORK_LIMIT:
            network_only.sort(key=lambda entry: entry["network_heard"], reverse=True)
            for entry in network_only[NETWORK_LIMIT:]:
                del self._entries[entry["address"]]
