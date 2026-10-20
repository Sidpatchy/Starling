"""RRC 0.1 client over reference Reticulum Links.

This module intentionally implements only the versioned core wire protocol:
HELLO/WELCOME, JOIN/PART, MSG/ACTION, NOTICE, PING/PONG, and ERROR.
"""

import queue
import secrets
import base64
import json
import struct
import threading
import time


PROTOCOL_VERSION = 1
HELLO = 1
WELCOME = 2
JOIN = 10
JOINED = 11
PART = 12
PARTED = 13
MSG = 20
NOTICE = 21
ACTION = 22
PING = 30
PONG = 31
ERROR = 40

_events = queue.Queue()
_lock = threading.RLock()
_identity = None
_destination = None
_link = None
_display_name = "Starling"
_hub_name = None
_connection_state = "disconnected"
_joined_rooms = set()
_joining_rooms = set()
_parting_rooms = set()
_pending_joins = []
_pending_parts = []
_pending_messages = []
_welcome_timer = None
_connect_generation = 0

# Keepalive: once an RRC hub has welcomed the connection, Starling sends a
# PING every _KEEPALIVE_INTERVAL seconds and expects the matching PONG back
# within _PONG_TIMEOUT seconds. A link whose hub stops answering keepalives is
# treated as failed so the UI can reconnect instead of silently black-holing
# outgoing messages.
_KEEPALIVE_INTERVAL = 45.0
_PONG_TIMEOUT = 15.0

_keepalive_timer = None
_pong_timer = None
_outstanding_ping = None


def _emit(kind, **fields):
    event = {"kind": kind}
    event.update(fields)
    _events.put(event)


def _head(major, value):
    if value < 0:
        raise ValueError("CBOR lengths and unsigned integers must not be negative")
    if value < 24:
        return bytes([(major << 5) | value])
    if value < 256:
        return bytes([(major << 5) | 24, value])
    if value < 65536:
        return bytes([(major << 5) | 25]) + value.to_bytes(2, "big")
    if value < 4294967296:
        return bytes([(major << 5) | 26]) + value.to_bytes(4, "big")
    if value < 18446744073709551616:
        return bytes([(major << 5) | 27]) + value.to_bytes(8, "big")
    raise ValueError("CBOR integer exceeds 64 bits")


def cbor_encode(value):
    if value is None:
        return b"\xf6"
    if value is False:
        return b"\xf4"
    if value is True:
        return b"\xf5"
    if isinstance(value, float):
        return b"\xfb" + struct.pack(">d", value)
    if isinstance(value, int):
        return _head(0, value) if value >= 0 else _head(1, -1 - value)
    if isinstance(value, bytes):
        return _head(2, len(value)) + value
    if isinstance(value, str):
        raw = value.encode("utf-8")
        return _head(3, len(raw)) + raw
    if isinstance(value, (list, tuple)):
        return _head(4, len(value)) + b"".join(cbor_encode(item) for item in value)
    if isinstance(value, dict):
        return _head(5, len(value)) + b"".join(cbor_encode(key) + cbor_encode(item) for key, item in value.items())
    raise TypeError("Unsupported RRC CBOR value: " + type(value).__name__)


def cbor_decode(data):
    view = memoryview(data)

    def read_length(additional, offset):
        if additional < 24:
            return additional, offset
        width = {24: 1, 25: 2, 26: 4, 27: 8}.get(additional)
        if width is None or offset + width > len(view):
            raise ValueError("Invalid or truncated CBOR length")
        return int.from_bytes(view[offset:offset + width], "big"), offset + width

    def read(offset):
        if offset >= len(view):
            raise ValueError("Truncated CBOR value")
        initial = view[offset]
        major, additional = initial >> 5, initial & 31
        offset += 1
        if major in (0, 1):
            value, offset = read_length(additional, offset)
            return (value if major == 0 else -1 - value), offset
        if major in (2, 3):
            size, offset = read_length(additional, offset)
            end = offset + size
            if end > len(view):
                raise ValueError("Truncated CBOR string")
            raw = bytes(view[offset:end])
            return (raw if major == 2 else raw.decode("utf-8")), end
        if major == 4:
            size, offset = read_length(additional, offset)
            result = []
            for _ in range(size):
                item, offset = read(offset)
                result.append(item)
            return result, offset
        if major == 5:
            size, offset = read_length(additional, offset)
            result = {}
            for _ in range(size):
                key, offset = read(offset)
                item, offset = read(offset)
                result[key] = item
            return result, offset
        if major == 7:
            if additional == 20:
                return False, offset
            if additional == 21:
                return True, offset
            if additional == 22:
                return None, offset
            if additional == 25:
                if offset + 2 > len(view):
                    raise ValueError("Truncated CBOR float")
                return struct.unpack(">e", view[offset:offset + 2])[0], offset + 2
            if additional == 26:
                if offset + 4 > len(view):
                    raise ValueError("Truncated CBOR float")
                return struct.unpack(">f", view[offset:offset + 4])[0], offset + 4
            if additional == 27:
                if offset + 8 > len(view):
                    raise ValueError("Truncated CBOR float")
                return struct.unpack(">d", view[offset:offset + 8])[0], offset + 8
        raise ValueError("Unsupported CBOR type")

    decoded, consumed = read(0)
    if consumed != len(view):
        raise ValueError("Trailing bytes after CBOR value")
    return decoded


def _envelope(message_type, room=None, body=None, nickname=None):
    if _identity is None:
        raise RuntimeError("Reticulum identity is not initialized")
    message = {
        0: PROTOCOL_VERSION,
        1: message_type,
        2: secrets.token_bytes(8),
        3: int(time.time() * 1000),
        4: _identity.hash,
    }
    if room is not None:
        message[5] = room
    if body is not None:
        message[6] = body
    if nickname:
        message[7] = nickname
    return message


def _normalize_nickname(value):
    nickname = (value or "Starling").strip() or "Starling"
    while len(nickname.encode("utf-8")) > 32:
        nickname = nickname[:-1]
    return nickname


def _send(message_type, room=None, body=None, nickname=None):
    import RNS

    link = _link
    if link is None or link.status != link.ACTIVE:
        raise RuntimeError("RRC Link is not active")
    packet = cbor_encode(_envelope(message_type, room, body, nickname))
    if len(packet) > link.mdu:
        raise ValueError("Message is too large for this Reticulum Link (maximum {} bytes)".format(link.mdu))
    receipt = RNS.Packet(link, packet).send()
    if receipt is False:
        raise RuntimeError("Reticulum could not send the RRC packet")


def _link_established(link):
    global _connection_state, _welcome_timer
    with _lock:
        if link is not _link:
            return
        _connection_state = "connecting"
    try:
        link.identify(_identity)
        _send(HELLO, body={0: "Starling", 1: "0.1.0", 2: {}}, nickname=_display_name)
        _welcome_timer = threading.Timer(30, _welcome_timeout, args=(link,))
        _welcome_timer.daemon = True
        _welcome_timer.start()
        _emit("connection", state="connecting")
    except Exception as error:
        _emit("connection", state="failed", message=str(error))
        link.teardown()


def _link_closed(link):
    global _link, _connection_state, _hub_name, _welcome_timer
    with _lock:
        if link is not _link:
            return
        _link = None
        _joined_rooms.clear()
        _joining_rooms.clear()
        _parting_rooms.clear()
        _pending_joins.clear()
        _pending_parts.clear()
        _pending_messages.clear()
        was_active = _connection_state == "connected"
        _connection_state = "disconnected"
        _hub_name = None
        timer = _welcome_timer
        _welcome_timer = None
        _cancel_keepalive_locked()
    if timer is not None:
        timer.cancel()
    _emit(
        "connection",
        state="disconnected" if was_active else "failed",
        message="RRC Link closed" if was_active else "RRC Link failed to establish",
    )


def _welcome_timeout(link):
    global _connection_state
    with _lock:
        if link is not _link or _connection_state != "connecting":
            return
        _connection_state = "failed"
    _emit("connection", state="failed", message="The RRC hub did not send WELCOME within 30 seconds")
    link.teardown()


def _cancel_keepalive_locked():
    """Cancel outstanding keepalive timers. Callers must hold _lock."""
    global _keepalive_timer, _pong_timer, _outstanding_ping
    timer = _keepalive_timer
    _keepalive_timer = None
    pong_timer = _pong_timer
    _pong_timer = None
    _outstanding_ping = None
    if timer is not None:
        timer.cancel()
    if pong_timer is not None:
        pong_timer.cancel()


def _schedule_keepalive(link):
    """Arrange the next keepalive PING for an established connection."""
    global _keepalive_timer
    with _lock:
        if _connection_state != "connected" or link is not _link:
            return
        timer = _keepalive_timer
        _keepalive_timer = None
        if timer is not None:
            timer.cancel()
        _keepalive_timer = threading.Timer(_KEEPALIVE_INTERVAL, _send_keepalive, args=(link,))
        _keepalive_timer.daemon = True
        _keepalive_timer.start()


def _send_keepalive(link):
    """Send one keepalive PING and arm the PONG timeout for it."""
    global _pong_timer, _outstanding_ping
    with _lock:
        if _connection_state != "connected" or link is not _link:
            return
        if _outstanding_ping is not None:
            # A PING is already awaiting its PONG; the PONG timeout detects a
            # dead link, so do not stack another keepalive behind it.
            return
        payload = secrets.token_bytes(8)
        _outstanding_ping = payload
    try:
        _send(PING, body=payload)
    except Exception as error:
        with _lock:
            _outstanding_ping = None
        _emit(
            "error",
            message="Could not send RRC keepalive: " + (str(error) or "unknown error"),
            room="",
        )
        _schedule_keepalive(link)
        return
    with _lock:
        if _connection_state != "connected" or link is not _link:
            _outstanding_ping = None
            return
        timer = _pong_timer
        _pong_timer = None
        if timer is not None:
            timer.cancel()
        _pong_timer = threading.Timer(_PONG_TIMEOUT, _keepalive_pong_timeout, args=(link, payload))
        _pong_timer.daemon = True
        _pong_timer.start()


def _keepalive_pong_timeout(link, payload):
    """Treat the link as failed when the hub does not answer a keepalive PING."""
    global _connection_state, _pong_timer, _outstanding_ping
    with _lock:
        if (
            _outstanding_ping is None
            or _outstanding_ping != payload
            or link is not _link
            or _connection_state != "connected"
        ):
            return
        _outstanding_ping = None
        pong_timer = _pong_timer
        _pong_timer = None
        connection_link = _link
        _connection_state = "failed"
    if pong_timer is not None:
        pong_timer.cancel()
    connection_link.teardown()
    _emit(
        "connection",
        state="failed",
        message="The RRC hub did not respond to a keepalive ping",
    )


def _handle_pong(room, body):
    """Acknowledge a hub PONG that matches the outstanding keepalive PING."""
    global _pong_timer, _outstanding_ping
    with _lock:
        outstanding = _outstanding_ping
        if outstanding is None or body != outstanding:
            return
        if _connection_state != "connected" or _link is None:
            _outstanding_ping = None
            return
        _outstanding_ping = None
        pong_timer = _pong_timer
        _pong_timer = None
        link_ref = _link
    if pong_timer is not None:
        pong_timer.cancel()
    _schedule_keepalive(link_ref)


def _packet_received(data, packet):
    global _connection_state, _hub_name
    try:
        envelope = cbor_decode(data)
        if not isinstance(envelope, dict) or envelope.get(0) != PROTOCOL_VERSION:
            return
        message_type = envelope.get(1)
        room = envelope.get(5)
        body = envelope.get(6)

        if message_type == WELCOME:
            global _welcome_timer
            welcome = body if isinstance(body, dict) else {}
            _hub_name = welcome.get(0) if isinstance(welcome.get(0), str) else None
            with _lock:
                _connection_state = "connected"
                timer = _welcome_timer
                _welcome_timer = None
                joins = list(_pending_joins)
                parts = list(_pending_parts)
                _pending_joins.clear()
                _pending_parts.clear()
            if timer is not None:
                timer.cancel()
            _emit("connection", state="connected", hub_name=_hub_name or "")
            for pending_room in joins:
                _send_pending_room_command(JOIN, pending_room)
            for pending_room in parts:
                _send_pending_room_command(PART, pending_room)
            _schedule_keepalive(_link)
            return

        if message_type == JOINED and isinstance(room, str):
            normalized_room = room.casefold()
            with _lock:
                is_our_join = normalized_room in _joining_rooms
                if is_our_join:
                    _joining_rooms.discard(normalized_room)
                    _joined_rooms.add(normalized_room)
            member_ids = _member_hashes(body)
            if is_our_join:
                _emit("room_joined", room=room)
                if member_ids:
                    _emit("members", room=room, member_ids=[(member_id, _lxmf_address_for_identity(member_id)) for member_id in member_ids])
                _flush_pending_messages(room)
            else:
                nickname = envelope.get(7) if isinstance(envelope.get(7), str) else ""
                for member_id in member_ids:
                    _emit(
                        "member_joined",
                        room=room,
                        member_id=member_id,
                        member_name=nickname,
                        lxmf_address=_lxmf_address_for_identity(member_id),
                        timestamp=envelope.get(3, int(time.time() * 1000)),
                    )
            return
        if message_type == PARTED and isinstance(room, str):
            normalized_room = room.casefold()
            with _lock:
                is_our_part = normalized_room in _parting_rooms
                if is_our_part:
                    _parting_rooms.discard(normalized_room)
                    _joined_rooms.discard(normalized_room)
            member_ids = _member_hashes(body)
            if is_our_part:
                _emit("room_left", room=room)
            else:
                for member_id in member_ids:
                    _emit(
                        "member_left",
                        room=room,
                        member_id=member_id,
                        timestamp=envelope.get(3, int(time.time() * 1000)),
                    )
            return
        if message_type == PING:
            _send(PONG, room=room, body=body)
            return
        if message_type == PONG:
            _handle_pong(room, body)
            return
        if message_type == ERROR:
            message = body if isinstance(body, str) else "The RRC hub reported an error"
            _emit("error", message=message, room=room or "", timestamp=envelope.get(3, int(time.time() * 1000)))
            return

        if message_type in (MSG, NOTICE, ACTION) and (
            isinstance(room, str) or (message_type == NOTICE and room is None)
        ):
            sender = envelope.get(4, b"")
            if isinstance(sender, bytes):
                sender_id = sender.hex()
            else:
                sender_id = str(sender)
            nickname = envelope.get(7)
            if not isinstance(nickname, str) or not nickname.strip():
                nickname = sender_id[:12] if sender_id else "unknown"
            if isinstance(body, bytes):
                text = body.decode("utf-8", errors="replace")
            elif isinstance(body, str):
                text = body
            else:
                text = str(body) if body is not None else ""
            _emit(
                "message",
                message_id=envelope.get(2, b"").hex() if isinstance(envelope.get(2), bytes) else "",
                room=room or "",
                sender_id=sender_id,
                sender_name=nickname,
                body=text,
                timestamp=envelope.get(3, int(time.time() * 1000)),
                is_action=message_type == ACTION,
                is_notice=message_type == NOTICE,
                is_mine=bool(_identity is not None and sender == _identity.hash),
                sender_lxmf_address=_lxmf_address_for_identity(sender) if isinstance(sender, bytes) else "",
            )
    except Exception as error:
        _emit("error", message="Could not decode RRC packet: " + str(error), room="")


def connect(destination_hash_hex, display_name):
    global _identity, _destination, _link, _display_name, _connection_state, _connect_generation
    import RNS

    normalized = destination_hash_hex.strip().lower()
    if len(normalized) != RNS.Reticulum.TRUNCATED_HASHLENGTH // 4:
        raise ValueError("RRC hub destination must be a 32-character hexadecimal hash")
    try:
        destination_hash = bytes.fromhex(normalized)
    except ValueError:
        raise ValueError("RRC hub destination must contain only hexadecimal characters")

    with _lock:
        if _link is not None and _link.status != _link.CLOSED:
            raise RuntimeError("Disconnect from the current RRC hub before connecting to another")
        _connect_generation += 1
        generation = _connect_generation
        _connection_state = "connecting"
        _display_name = _normalize_nickname(display_name)

    try:
        from starling_identity import load_identity

        _identity = load_identity(RNS.Reticulum.configdir)

        _emit("self", member_id=_identity.hash.hex(), member_name=_display_name)

        recalled = RNS.Identity.recall(destination_hash)
        if recalled is None:
            _emit("connection", state="connecting", message="Requesting the RRC hub path…")
            RNS.Transport.request_path(destination_hash)
    except Exception as error:
        with _lock:
            _connection_state = "failed"
        _emit("connection", state="failed", message=str(error) or "Could not prepare the RRC identity")
        raise
    worker = threading.Thread(
        target=_finish_connect,
        args=(destination_hash, generation, recalled),
        name="starling-rrc-path",
        daemon=True,
    )
    worker.start()


def _finish_connect(destination_hash, generation, recalled):
    global _destination, _link, _connection_state
    import RNS

    if recalled is None:
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            with _lock:
                if generation != _connect_generation:
                    return
            recalled = RNS.Identity.recall(destination_hash)
            if recalled is not None:
                break
            time.sleep(0.25)
    if recalled is None:
        with _lock:
            if generation != _connect_generation:
                return
            _connection_state = "failed"
        _emit("connection", state="failed", message="Could not discover the RRC hub. Check its destination hash and Reticulum path.")
        return

    try:
        destination = RNS.Destination(recalled, RNS.Destination.OUT, RNS.Destination.SINGLE, "rrc", "hub")
        if destination.hash != destination_hash:
            raise ValueError("The supplied destination is not an rrc.hub address")
        with _lock:
            if generation != _connect_generation:
                return
            link = RNS.Link(destination, established_callback=_link_established, closed_callback=_link_closed)
            link.set_packet_callback(_packet_received)
            _destination = destination
            _link = link
            _joined_rooms.clear()
            _joining_rooms.clear()
            _parting_rooms.clear()
            _pending_messages.clear()
        _emit("connection", state="connecting")
    except Exception as error:
        with _lock:
            if generation != _connect_generation:
                return
            _connection_state = "failed"
        _emit("connection", state="failed", message=str(error) or "Could not open an RRC Link")


def disconnect():
    global _link, _destination, _identity, _connection_state, _hub_name, _welcome_timer, _connect_generation
    with _lock:
        _connect_generation += 1
        link = _link
        _link = None
        _destination = None
        _identity = None
        _connection_state = "disconnected"
        _hub_name = None
        timer = _welcome_timer
        _welcome_timer = None
        _cancel_keepalive_locked()
        _joined_rooms.clear()
        _joining_rooms.clear()
        _parting_rooms.clear()
        _pending_joins.clear()
        _pending_parts.clear()
        _pending_messages.clear()
    if timer is not None:
        timer.cancel()
    if link is not None and link.status != link.CLOSED:
        link.teardown()
    _emit("connection", state="disconnected")


def _normalize_room_name(room):
    room = room.strip()
    if not room:
        raise ValueError("Room name cannot be empty")
    if len(room.encode("utf-8")) > 64:
        raise ValueError("Room name exceeds the 64-byte RRC limit")
    return room


def _flush_pending_messages(room):
    normalized_room = room.casefold()
    with _lock:
        pending = []
        remaining = []
        for message in _pending_messages:
            if message[0].casefold() == normalized_room:
                pending.append(message)
            else:
                remaining.append(message)
        _pending_messages[:] = remaining

        # Keep sends serialized with send() so a new message cannot overtake
        # messages queued while this room's JOIN was being acknowledged.
        for pending_room, body, is_action in pending:
            try:
                _send(ACTION if is_action else MSG, room=pending_room, body=body, nickname=_display_name)
            except Exception as error:
                _emit(
                    "error",
                    message="Could not send queued RRC message: " + (str(error) or "unknown send error"),
                    room=pending_room,
                    timestamp=int(time.time() * 1000),
                )


def _send_pending_room_command(message_type, room):
    normalized_room = room.casefold()
    is_join = message_type == JOIN
    pending_rooms = _joining_rooms if is_join else _parting_rooms
    opposite_rooms = _parting_rooms if is_join else _joining_rooms
    operation = "join" if is_join else "leave"
    with _lock:
        pending_rooms.add(normalized_room)
        opposite_rooms.discard(normalized_room)
        try:
            _send(message_type, room=room, nickname=_display_name)
        except Exception as error:
            pending_rooms.discard(normalized_room)
            _emit(
                "error",
                message="Could not {} RRC room: {}".format(operation, str(error) or "unknown send error"),
                room=room,
                timestamp=int(time.time() * 1000),
            )


def join(room):
    room = _normalize_room_name(room)
    with _lock:
        if _connection_state == "connected":
            _joining_rooms.add(room.casefold())
            _parting_rooms.discard(room.casefold())
            _send(JOIN, room=room, nickname=_display_name)
        elif _connection_state == "connecting":
            if room.casefold() not in {entry.casefold() for entry in _pending_joins}:
                _pending_joins.append(room)
            _pending_parts[:] = [entry for entry in _pending_parts if entry.casefold() != room.casefold()]
        else:
            raise RuntimeError("Connect to an RRC hub before joining a room")


def part(room):
    room = _normalize_room_name(room)
    with _lock:
        if _connection_state == "connected":
            _parting_rooms.add(room.casefold())
            _send(PART, room=room, nickname=_display_name)
            _joining_rooms.discard(room.casefold())
            _joined_rooms.discard(room.casefold())
        elif _connection_state == "connecting":
            _pending_parts.append(room)
            _pending_joins[:] = [entry for entry in _pending_joins if entry.casefold() != room.casefold()]
        else:
            raise RuntimeError("Connect to an RRC hub before leaving a room")


def send(room, body, is_action=False):
    room = room.strip()
    body = body.strip()
    if not body:
        return
    with _lock:
        if _connection_state not in ("connected", "connecting"):
            raise RuntimeError("The RRC hub has not welcomed this client yet")
        if _connection_state == "connecting":
            # The hub has not welcomed this client yet, but a connection is in
            # progress. Queue the message instead of dropping it so user input
            # is not lost while the hub path is still being resolved; it is
            # sent once the room is joined and the welcome has arrived.
            if len(_pending_messages) >= 32:
                raise RuntimeError("Too many RRC messages are waiting for the room join")
            _pending_messages.append((room, body, is_action))
            return
        normalized = room.casefold()
        if normalized not in _joined_rooms:
            if normalized not in _joining_rooms:
                _send(JOIN, room=room, nickname=_display_name)
                _joining_rooms.add(normalized)
            if len(_pending_messages) >= 32:
                raise RuntimeError("Too many RRC messages are waiting for the room join")
            _pending_messages.append((room, body, is_action))
            return
        _send(ACTION if is_action else MSG, room=room, body=body, nickname=_display_name)


def poll_events(limit=100):
    result = []
    for _ in range(limit):
        try:
            result.append(_events.get_nowait())
        except queue.Empty:
            break
    return result


def _b64(value):
    if isinstance(value, bytes):
        raw = value
    else:
        raw = str(value).encode("utf-8")
    return base64.b64encode(raw).decode("ascii")


def poll_event_lines(limit=100):
    lines = []
    for event in poll_events(limit):
        kind = event.get("kind")
        if kind == "connection":
            lines.append("CONNECTION\t{}\t{}\t{}".format(
                event.get("state", "disconnected"),
                _b64(event.get("hub_name", "")),
                _b64(event.get("message", "")),
            ))
        elif kind == "self":
            lines.append("SELF\t{}\t{}".format(
                _b64(event.get("member_id", "")),
                _b64(event.get("member_name", "")),
            ))
        elif kind == "room_joined":
            lines.append("JOINED\t" + _b64(event.get("room", "")))
        elif kind == "room_left":
            lines.append("LEFT\t" + _b64(event.get("room", "")))
        elif kind == "members":
            members = ";".join("{}={}".format(member_id, address) for member_id, address in event.get("member_ids", []))
            lines.append("MEMBERS\t{}\t{}".format(_b64(event.get("room", "")), members))
        elif kind == "member_joined":
            lines.append("MEMBER_JOINED\t{}\t{}\t{}\t{}\t{}".format(
                _b64(event.get("room", "")),
                _b64(event.get("member_id", "")),
                _b64(event.get("member_name", "")),
                _b64(event.get("lxmf_address", "")),
                int(event.get("timestamp", 0)),
            ))
        elif kind == "member_left":
            lines.append("MEMBER_LEFT\t{}\t{}\t{}".format(
                _b64(event.get("room", "")),
                _b64(event.get("member_id", "")),
                int(event.get("timestamp", 0)),
            ))
        elif kind == "message":
            lines.append("MESSAGE\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}".format(
                _b64(event.get("message_id", "")),
                _b64(event.get("room", "")),
                _b64(event.get("sender_id", "")),
                _b64(event.get("sender_name", "")),
                _b64(event.get("body", "")),
                int(event.get("timestamp", 0)),
                1 if event.get("is_action") else 0,
                1 if event.get("is_notice") else 0,
                1 if event.get("is_mine") else 0,
                _b64(event.get("sender_lxmf_address", "")),
            ))
        elif kind == "error":
            lines.append("ERROR\t{}\t{}\t{}".format(
                _b64(event.get("message", "")),
                _b64(event.get("room", "")),
                int(event.get("timestamp", 0)),
            ))
    return "\n".join(lines)


def current_connection_event_line():
    with _lock:
        state = _connection_state
        hub_name = _hub_name or ""
    return "CONNECTION\t{}\t{}\t".format(state, _b64(hub_name))


def current_self_event_line():
    with _lock:
        identity = _identity
        display_name = _display_name
    if identity is None:
        return "SELF\t\t"
    return "SELF\t{}\t{}".format(_b64(identity.hash.hex()), _b64(display_name))


def _member_hashes(value):
    if not isinstance(value, list):
        return []
    hashes = []
    for member in value:
        if isinstance(member, bytes):
            hashes.append(member.hex())
        elif isinstance(member, str):
            hashes.append(member)
    return hashes


def _lxmf_address_for_identity(identity_hash):
    try:
        import RNS

        if isinstance(identity_hash, str):
            identity_hash = bytes.fromhex(identity_hash)
        if len(identity_hash) != RNS.Identity.TRUNCATED_HASHLENGTH // 8:
            return ""
        # An LXMF delivery destination hash is deterministic from the public
        # identity hash, so contacts don't need to have been separately
        # announced to this node before their address can be offered.
        return RNS.Destination.hash(identity_hash, "lxmf", "delivery").hex()
    except Exception:
        return ""
