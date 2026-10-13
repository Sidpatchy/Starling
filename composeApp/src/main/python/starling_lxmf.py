"""LXMF direct-delivery adapter using the reference LXMF router."""

import base64
import queue
import re
import threading
import time

_lock = threading.RLock()
_events = queue.Queue()
_router = None
_identity = None
_delivery_destination = None
_data_dir = None
_display_name = "Starling"
_send_threads = set()
_verification_threads = set()
_shutdown = threading.Event()


def _emit(kind, **fields):
    event = {"kind": kind}
    event.update(fields)
    _events.put(event)


def _encode(value):
    if isinstance(value, bytes):
        raw = value
    else:
        raw = str(value).encode("utf-8")
    return base64.b64encode(raw).decode("ascii")


def _identity_for_config(config_dir):
    from starling_identity import load_identity

    return load_identity(config_dir)


def start(config_dir, display_name="Starling"):
    """Start the local LXMF delivery destination, reusing Starling's RNS identity."""
    global _router, _identity, _delivery_destination, _data_dir, _display_name
    import LXMF
    import RNS

    with _lock:
        if _router is not None:
            return local_address()

        _shutdown.clear()
        _data_dir = config_dir
        _identity = _identity_for_config(config_dir)
        _emit("identity", identity_hash=_identity.hash.hex())
        import starling_rns

        _router = starling_rns.construct_without_required_process_signals(lambda: LXMF.LXMRouter(
            identity=_identity,
            storagepath=config_dir,
            name="Starling",
            autopeer=False,
        ))
        _display_name = _normalize_display_name(display_name)
        _delivery_destination = _router.register_delivery_identity(
            _identity,
            display_name=_display_name,
        )
        if _delivery_destination is None:
            _router = None
            raise RuntimeError("LXMF could not register Starling's delivery identity")
        _router.register_delivery_callback(_delivery_received)
        _router.announce(_delivery_destination.hash)
        address = _delivery_destination.hash.hex()
    _emit("local_address", address=address)
    return address


def _normalize_display_name(value):
    name = (value or "Starling").strip() or "Starling"
    while len(name.encode("utf-8")) > 32:
        name = name[:-1]
    return name


def set_display_name(display_name):
    global _display_name
    name = _normalize_display_name(display_name)
    with _lock:
        _display_name = name
        destination = _delivery_destination
        router = _router
        if destination is not None:
            destination.display_name = name
    if destination is not None and router is not None:
        router.announce(destination.hash)
    return name


def local_address():
    with _lock:
        if _delivery_destination is None:
            return ""
        return _delivery_destination.hash.hex()


def identity_hash():
    with _lock:
        if _identity is None:
            return ""
        return _identity.hash.hex()


def _peer_name(source_hash):
    try:
        import RNS

        app_data = RNS.Identity.recall_app_data(source_hash)
        if not app_data:
            return ""
        decoded = RNS.vendor.umsgpack.unpackb(app_data)
        if isinstance(decoded, list) and decoded and isinstance(decoded[0], bytes):
            return decoded[0].decode("utf-8", errors="replace").strip()
    except Exception:
        pass
    return ""


def _delivery_received(message):
    import LXMF

    try:
        if message.signature_validated:
            _deliver_verified_message(message)
            return

        if message.unverified_reason == LXMF.LXMessage.SOURCE_UNKNOWN:
            _resolve_unknown_source_async(message.packed, message.source_hash)
            return

        if message.unverified_reason == LXMF.LXMessage.SIGNATURE_INVALID:
            _emit(
                "error",
                message="Dropped an LXMF message with an invalid sender signature.",
                address=message.source_hash.hex(),
            )
            return

        _emit(
            "error",
            message="Dropped an LXMF message whose sender identity could not be verified.",
            address=message.source_hash.hex(),
        )
    except Exception as error:
        _emit("error", message="Could not validate an LXMF message: " + str(error))


def _deliver_verified_message(message):
    source_hash = message.source_hash.hex()
    _emit(
        "direct_message",
        message_id=message.hash.hex(),
        address=source_hash,
        sender_name=_peer_name(message.source_hash),
        body=message.content_as_string() or "",
        timestamp=int((message.timestamp or time.time()) * 1000),
        is_mine=False,
    )


def _resolve_unknown_source_async(packed_message, source_hash):
    worker = threading.Thread(
        target=_resolve_unknown_source,
        args=(packed_message, source_hash),
        name="starling-lxmf-verify-source",
        daemon=True,
    )
    with _lock:
        _verification_threads.add(worker)
    worker.start()


def _resolve_unknown_source(packed_message, source_hash):
    import LXMF
    import RNS

    try:
        RNS.Transport.request_path(source_hash)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline and not _shutdown.is_set():
            if RNS.Identity.recall(source_hash, _no_use=True) is not None:
                verified_message = LXMF.LXMessage.unpack_from_bytes(packed_message)
                if verified_message.signature_validated:
                    _deliver_verified_message(verified_message)
                    return
                if verified_message.unverified_reason == LXMF.LXMessage.SIGNATURE_INVALID:
                    _emit(
                        "error",
                        message="Dropped an LXMF message with an invalid sender signature after resolving its identity.",
                        address=source_hash.hex(),
                    )
                    return
            time.sleep(0.25)
        if not _shutdown.is_set():
            _emit(
                "error",
                message="Could not resolve the sender identity to verify this LXMF message; it was withheld.",
                address=source_hash.hex(),
            )
    except Exception as error:
        if not _shutdown.is_set():
            _emit(
                "error",
                message="Could not verify this LXMF sender identity: " + str(error),
                address=source_hash.hex(),
            )
    finally:
        with _lock:
            _verification_threads.discard(threading.current_thread())


def _normalize_address(address):
    value = (address or "").strip()
    if value.lower().startswith("lxmf://"):
        value = value[7:]
    value = value.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{32}", value):
        raise ValueError("Enter the 32-character LXMF delivery destination hash (or lxmf:// followed by it).")
    return bytes.fromhex(value), value


def send_direct(address, body):
    """Resolve a peer asynchronously and queue a reference LXMF message."""
    text = (body or "").strip()
    if not text:
        return
    with _lock:
        if _router is None or _delivery_destination is None:
            raise RuntimeError("Start the Reticulum node before sending LXMF messages")
    destination_hash, normalized_address = _normalize_address(address)
    worker = threading.Thread(
        target=_send_resolved,
        args=(destination_hash, normalized_address, text),
        name="starling-lxmf-send",
        daemon=True,
    )
    with _lock:
        _send_threads.add(worker)
    worker.start()


def _send_resolved(destination_hash, normalized_address, text):
    import LXMF
    import RNS

    try:
        identity = RNS.Identity.recall(destination_hash)
        if identity is None:
            RNS.Transport.request_path(destination_hash)
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and not _shutdown.is_set():
                identity = RNS.Identity.recall(destination_hash)
                if identity is not None:
                    break
                time.sleep(0.25)
        if identity is None:
            if _shutdown.is_set():
                return
            raise RuntimeError("Could not discover this LXMF destination. Check the address and Reticulum path.")

        destination = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        if destination.hash != destination_hash:
            raise RuntimeError("The address does not match an LXMF delivery destination")
        with _lock:
            router = _router
            source = _delivery_destination
        if router is None or source is None:
            raise RuntimeError("The local LXMF delivery destination has stopped")

        message = LXMF.LXMessage(
            destination,
            source,
            content=text,
            desired_method=LXMF.LXMessage.OPPORTUNISTIC,
            include_ticket=True,
        )
        message.register_delivery_callback(_outbound_delivered)
        message.register_failed_callback(_outbound_failed)
        router.handle_outbound(message)
        _emit(
            "direct_message",
            message_id=message.hash.hex(),
            address=normalized_address,
            sender_name="",
            body=text,
            timestamp=int((message.timestamp or time.time()) * 1000),
            is_mine=True,
        )
        _emit("delivery_status", message_id=message.hash.hex(), state="queued", address=normalized_address)
    except Exception as error:
        _emit("error", message=str(error) or "Could not queue the LXMF message", address=normalized_address)
    finally:
        with _lock:
            _send_threads.discard(threading.current_thread())


def _outbound_delivered(message):
    state = "delivered" if message.state == message.DELIVERED else "sent"
    _emit("delivery_status", message_id=message.hash.hex(), state=state, address=message.destination_hash.hex())


def _outbound_failed(message):
    _emit("delivery_status", message_id=message.hash.hex(), state="failed", address=message.destination_hash.hex())


def poll_events(limit=100):
    result = []
    for _ in range(limit):
        try:
            result.append(_events.get_nowait())
        except queue.Empty:
            break
    return result


def poll_event_lines(limit=100):
    lines = []
    for event in poll_events(limit):
        kind = event.get("kind")
        if kind == "identity":
            lines.append("IDENTITY\t" + _encode(event.get("identity_hash", "")))
        elif kind == "local_address":
            lines.append("LOCAL_ADDRESS\t" + _encode(event.get("address", "")))
        elif kind == "direct_message":
            lines.append("DIRECT_MESSAGE\t{}\t{}\t{}\t{}\t{}\t{}".format(
                _encode(event.get("message_id", "")),
                _encode(event.get("address", "")),
                _encode(event.get("sender_name", "")),
                _encode(event.get("body", "")),
                int(event.get("timestamp", 0)),
                1 if event.get("is_mine") else 0,
            ))
        elif kind == "delivery_status":
            lines.append("DELIVERY_STATUS\t{}\t{}\t{}".format(
                _encode(event.get("message_id", "")),
                _encode(event.get("address", "")),
                event.get("state", "queued"),
            ))
        elif kind == "error":
            lines.append("LXMF_ERROR\t{}\t{}".format(
                _encode(event.get("message", "")),
                _encode(event.get("address", "")),
            ))
    return "\n".join(lines)


def current_local_address_event_line():
    return "LOCAL_ADDRESS\t" + _encode(local_address())


def current_identity_event_line():
    return "IDENTITY\t" + _encode(identity_hash())


def stop():
    global _router, _identity, _delivery_destination
    with _lock:
        _shutdown.set()
        router = _router
        send_threads = list(_send_threads)
        verification_threads = list(_verification_threads)
        _router = None
        _identity = None
        _delivery_destination = None
    for worker in send_threads:
        if worker is not threading.current_thread():
            worker.join(timeout=0.5)
    for worker in verification_threads:
        if worker is not threading.current_thread():
            worker.join(timeout=0.5)
    if router is not None:
        try:
            router.exit_handler()
        except Exception:
            pass
