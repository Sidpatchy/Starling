"""RRC adapter regressions that do not require RNS, LXMF, or a network."""

import queue
import sys
import threading
import time
import types
import unittest
from unittest.mock import patch

import starling_rrc as rrc

HASH_HEX = "0123456789abcdef0123456789abcdef"
HASH_BYTES = bytes.fromhex(HASH_HEX)


class _FakeIdentity:
    def __init__(self, hex_hash=HASH_HEX):
        self.hash = bytes.fromhex(hex_hash)


class _FakeRNS:
    class Identity:
        @staticmethod
        def recall(hash_hex):
            return None

    class Transport:
        @staticmethod
        def request_path(hash_hex):
            pass


def _install_rns_fake():
    rns_module = types.ModuleType("RNS")
    rns_module.Identity = _FakeRNS.Identity
    rns_module.Transport = _FakeRNS.Transport
    return rns_module


class _FakeLink:
    def __init__(self, status="active"):
        self.status = status
        self.ACTIVE = "active"
        self.CLOSED = "closed"
        self.teardown_called = False

    def teardown(self):
        self.teardown_called = True


class _FakeTimer:
    instances = []

    def __init__(self, interval, function, args=(), kwargs=None):
        self.function = function
        self.args = args
        self.cancelled = False
        self.fired = False
        _FakeTimer.instances.append(self)

    def start(self):
        pass

    def cancel(self):
        self.cancelled = True

    def fire(self):
        self.fired = True
        self.function(*self.args)


def _connected_state(**overrides):
    state = {
        "_connection_state": "connected",
        "_joined_rooms": set(),
        "_joining_rooms": set(),
        "_parting_rooms": set(),
        "_pending_messages": [],
        "_pending_joins": [],
        "_pending_parts": [],
        "_events": queue.Queue(),
        "_display_name": "Moss",
        "_identity": _FakeIdentity(),
        "_link": None,
        "_destination": None,
        "_welcome_timer": None,
    }
    state.update(overrides)
    return state


class RrcAdapterTest(unittest.TestCase):
    def setUp(self):
        rrc._connection_state = "disconnected"
        rrc._joined_rooms = set()
        rrc._joining_rooms = set()
        rrc._parting_rooms = set()
        rrc._pending_messages = []
        rrc._pending_joins = []
        rrc._pending_parts = []
        rrc._events = queue.Queue()
        rrc._display_name = "Starling"
        rrc._identity = None
        rrc._link = None
        rrc._destination = None
        rrc._welcome_timer = None
        rrc._connect_generation = 0
        rrc._keepalive_timer = None
        rrc._pong_timer = None
        rrc._outstanding_ping = None
        rrc._keepalive_timer = None
        rrc._pong_timer = None
        rrc._outstanding_ping = None
        self._replaced_modules = []

    def tearDown(self):
        for name, previous in self._replaced_modules:
            if previous is None:
                try:
                    del sys.modules[name]
                except KeyError:
                    pass
            else:
                sys.modules[name] = previous
        rrc._connection_state = "disconnected"
        rrc._joined_rooms = set()
        rrc._joining_rooms = set()
        rrc._parting_rooms = set()
        rrc._pending_messages = []
        rrc._pending_joins = []
        rrc._pending_parts = []
        rrc._events = queue.Queue()
        rrc._display_name = "Starling"
        rrc._identity = None
        rrc._link = None
        rrc._destination = None
        rrc._welcome_timer = None
        rrc._connect_generation = 0
        rrc._keepalive_timer = None
        rrc._pong_timer = None
        rrc._outstanding_ping = None

    def _with_rns(self):
        import sys

        rns_module = _install_rns_fake()
        self._replaced_modules.append(("RNS", sys.modules.get("RNS")))
        sys.modules["RNS"] = rns_module

    def test_send_while_connecting_queues_instead_of_dropping(self):
        with patch.multiple(
            rrc,
            _connection_state="connecting",
            _joined_rooms=set(),
            _joining_rooms=set(),
            _pending_messages=[],
            _events=queue.Queue(),
            _display_name="Moss",
            _identity=_FakeIdentity(),
        ), patch.object(rrc, "_send") as send:
            rrc.send("general", "hello")
            rrc.send("general", "goodbye", is_action=True)

            send.assert_not_called()
            self.assertEqual(
                rrc._pending_messages,
                [("general", "hello", False), ("general", "goodbye", True)],
            )

    def test_send_with_empty_body_while_connecting_returns_early(self):
        with patch.multiple(
            rrc,
            _connection_state="connecting",
            _pending_messages=[],
            _events=queue.Queue(),
            _identity=_FakeIdentity(),
        ), patch.object(rrc, "_send") as send:
            rrc.send("general", "   ")
            rrc.send("general", "\t\n")

            send.assert_not_called()
            self.assertEqual(rrc._pending_messages, [])

    def test_queued_connecting_messages_flush_when_room_joins(self):
        with patch.multiple(
            rrc,
            _connection_state="connecting",
            _joined_rooms=set(),
            _joining_rooms=set(),
            _pending_messages=[],
            _events=queue.Queue(),
            _display_name="Moss",
            _identity=_FakeIdentity(),
        ), patch.object(rrc, "_send") as send:
            rrc.send("general", "first message")
            rrc.send("General", "second message", is_action=True)
            # A message for another room must not be flushed by this join.
            rrc._pending_messages.append(("other", "keep for other room", False))

            # Simulate the hub welcoming the connection, then acknowledging the
            # room join while still connected.
            with patch.multiple(
                rrc,
                _connection_state="connected",
                _joining_rooms={"general"},
            ), patch.object(
                rrc,
                "cbor_decode",
                return_value={0: rrc.PROTOCOL_VERSION, 1: rrc.JOINED, 5: "General"},
            ):
                rrc._packet_received(b"", None)

            self.assertEqual(rrc._pending_messages, [("other", "keep for other room", False)])
            self.assertIn("general", rrc._joined_rooms)
            self.assertEqual(
                send.call_args_list,
                [
                    unittest.mock.call(rrc.MSG, room="general", body="first message", nickname="Moss"),
                    unittest.mock.call(rrc.ACTION, room="General", body="second message", nickname="Moss"),
                ],
            )

    def test_send_while_connecting_honors_the_queue_limit(self):
        with patch.multiple(
            rrc,
            _connection_state="connecting",
            _joined_rooms=set(),
            _joining_rooms=set(),
            _pending_messages=[("room", "message {}".format(index), False) for index in range(32)],
            _events=queue.Queue(),
            _identity=_FakeIdentity(),
        ), patch.object(rrc, "_send"):
            with self.assertRaisesRegex(RuntimeError, "Too many RRC messages are waiting"):
                rrc.send("room", "overflow message")

    def test_send_before_connect_raises(self):
        with patch.object(rrc, "_send") as send:
            with self.assertRaisesRegex(RuntimeError, "welcomed this client yet"):
                rrc.send("general", "hello")
            send.assert_not_called()

    def test_send_after_failed_connect_raises(self):
        with patch.multiple(
            rrc,
            _connection_state="failed",
            _pending_messages=[],
            _events=queue.Queue(),
            _identity=_FakeIdentity(),
        ), patch.object(rrc, "_send") as send:
            with self.assertRaisesRegex(RuntimeError, "welcomed this client yet"):
                rrc.send("general", "hello")
            send.assert_not_called()

    def test_join_and_part_reject_invalid_room_names(self):
        with patch.multiple(
            rrc,
            _connection_state="connected",
            _joined_rooms=set(),
            _joining_rooms=set(),
            _parting_rooms=set(),
            _display_name="Moss",
        ), patch.object(rrc, "_send"):
            for invalid_room in ("", "   ", "\t", "🌍" * 17):
                for operation in (rrc.join, rrc.part):
                    with self.subTest(operation=operation.__name__, room=repr(invalid_room)):
                        with self.assertRaises(ValueError):
                            operation(invalid_room)

    def test_disconnect_during_connect_aborts_the_path_wait_without_error(self):
        self._with_rns()
        resolution_release = threading.Event()
        rns_module = __import__("RNS")
        original_request_path = rns_module.Transport.request_path

        def blocking_request_path(hash_hex):
            resolution_release.wait(timeout=10)

        rns_module.Transport.request_path = blocking_request_path

        with patch.multiple(
            rrc,
            _connection_state="connecting",
            _connect_generation=1,
            _joined_rooms=set(),
            _joining_rooms=set(),
            _parting_rooms=set(),
            _pending_joins=[],
            _pending_parts=[],
            _events=queue.Queue(),
            _display_name="Moss",
            _identity=_FakeIdentity(),
            _link=None,
            _destination=None,
            _welcome_timer=None,
        ):
            worker = threading.Thread(
                target=rrc._finish_connect,
                args=(HASH_BYTES, 1, None),
                name="starling-rrc-connect",
                daemon=True,
            )
            worker.start()
            # Let the thread enter its resolution wait, then stop the connect.
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline and worker.is_alive():
                time.sleep(0.01)
            rrc.disconnect()
            worker.join(timeout=3)

        self.assertFalse(worker.is_alive(), "the connect wait should abort promptly on disconnect")
        self.assertEqual(rrc._connection_state, "disconnected")
        errors = [
            event
            for event in list(rrc._events.queue)
            if event.get("kind") == "error" or event.get("state") == "failed"
        ]
        self.assertEqual(errors, [], "a requested disconnect must not surface a spurious failure")
        resolution_release.set()
        rns_module.Transport.request_path = original_request_path

    def test_join_sends_command_while_connected(self):
        with patch.multiple(
            rrc,
            _connection_state="connected",
            _joined_rooms=set(),
            _joining_rooms=set(),
            _parting_rooms=set(),
            _display_name="Moss",
        ), patch.object(rrc, "_send") as send:
            rrc.join("  general  ")
            send.assert_called_once_with(rrc.JOIN, room="general", nickname="Moss")
            self.assertIn("general", rrc._joining_rooms)

    def _connected_state(self, link):
        return patch.multiple(
            rrc,
            _connection_state="connected",
            _link=link,
            _joined_rooms=set(),
            _joining_rooms=set(),
            _parting_rooms=set(),
            _pending_messages=[],
            _pending_joins=[],
            _pending_parts=[],
            _events=queue.Queue(),
            _display_name="Moss",
            _identity=_FakeIdentity(),
            _destination=None,
            _welcome_timer=None,
        )

    def test_keepalive_sends_ping_and_reschedules_after_matching_pong(self):
        _FakeTimer.instances = []
        link = _FakeLink()
        with patch.object(rrc.threading, "Timer", _FakeTimer), self._connected_state(link), patch.object(
            rrc, "_send"
        ) as send:
            rrc._schedule_keepalive(link)
            due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired]
            self.assertEqual(len(due), 1, "exactly one keepalive timer should be scheduled")
            due[0].fire()
            send.assert_called_once_with(rrc.PING, body=unittest.mock.ANY)
            payload = send.call_args.kwargs["body"]
            self.assertIsInstance(payload, bytes)
            self.assertEqual(len(payload), 8)
            due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired]
            self.assertEqual(len(due), 1, "exactly one PONG timeout should be armed")
            self.assertEqual(rrc._outstanding_ping, payload)

            # A PONG that does not match the outstanding payload is ignored.
            rrc._handle_pong(None, b"wrong-payload")
            self.assertEqual(rrc._outstanding_ping, payload)
            due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired]
            self.assertEqual(len(due), 1, "the PONG timeout must stay armed")

            # The matching PONG clears the outstanding ping and reschedules.
            rrc._handle_pong(None, payload)
            self.assertIsNone(rrc._outstanding_ping)
            due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired]
            self.assertEqual(len(due), 1)
            due[0].fire()
            self.assertEqual(send.call_count, 2)

    def test_keepalive_pong_timeout_fails_the_connection(self):
        _FakeTimer.instances = []
        link = _FakeLink()
        with patch.object(rrc.threading, "Timer", _FakeTimer), self._connected_state(link), patch.object(
            rrc, "_send"
        ) as send:
            rrc._schedule_keepalive(link)
            due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired]
            due[0].fire()
            payload = send.call_args.kwargs["body"]
            pong_due = [
                t
                for t in _FakeTimer.instances
                if not t.cancelled and not t.fired and t is not due[0]
            ]
            self.assertEqual(len(pong_due), 1)

            # No PONG arrives; the timeout fires and fails the connection.
            pong_due[0].fire()
            self.assertEqual(rrc._connection_state, "failed")
            self.assertTrue(link.teardown_called)
            self.assertIsNone(rrc._outstanding_ping)
            failures = [
                event
                for event in list(rrc._events.queue)
                if event.get("kind") == "connection" and event.get("state") == "failed"
            ]
            self.assertTrue(any("keepalive" in (event.get("message") or "") for event in failures))

    def test_keepalive_ignores_non_matching_pong_until_timeout(self):
        _FakeTimer.instances = []
        link = _FakeLink()
        with patch.object(rrc.threading, "Timer", _FakeTimer), self._connected_state(link), patch.object(
            rrc, "_send"
        ) as send:
            rrc._schedule_keepalive(link)
            due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired]
            due[0].fire()
            payload = send.call_args.kwargs["body"]
            pong_due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired][0]

            rrc._handle_pong(None, b"other-payload")
            self.assertEqual(rrc._outstanding_ping, payload)
            self.assertFalse(pong_due.cancelled, "a non-matching PONG must not disarm the timeout")

            pong_due.fire()
            self.assertEqual(rrc._connection_state, "failed")
            self.assertTrue(link.teardown_called)

    def test_disconnect_cancels_keepalive_timers(self):
        _FakeTimer.instances = []
        link = _FakeLink()
        with patch.object(rrc.threading, "Timer", _FakeTimer), self._connected_state(link), patch.object(
            rrc, "_send"
        ):
            rrc._schedule_keepalive(link)
            due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired]
            due[0].fire()
            self.assertTrue(any(not t.cancelled for t in _FakeTimer.instances))

            rrc.disconnect()
            self.assertEqual(rrc._connection_state, "disconnected")
            self.assertTrue(all(timer.cancelled for timer in _FakeTimer.instances))
            self.assertIsNone(rrc._outstanding_ping)
            self.assertTrue(link.teardown_called)

    def test_link_closed_cancels_keepalive_timers(self):
        _FakeTimer.instances = []
        link = _FakeLink()
        with patch.object(rrc.threading, "Timer", _FakeTimer), self._connected_state(link), patch.object(
            rrc, "_send"
        ):
            rrc._schedule_keepalive(link)
            due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired]
            due[0].fire()
            self.assertTrue(any(not t.cancelled for t in _FakeTimer.instances))

            rrc._link_closed(link)
            self.assertEqual(rrc._connection_state, "disconnected")
            self.assertTrue(all(timer.cancelled for timer in _FakeTimer.instances))
            self.assertIsNone(rrc._outstanding_ping)

    def test_welcome_schedules_keepalive(self):
        _FakeTimer.instances = []
        link = _FakeLink()
        with patch.object(rrc.threading, "Timer", _FakeTimer), patch.multiple(
            rrc,
            _link=link,
            _connection_state="connecting",
            _joined_rooms=set(),
            _joining_rooms=set(),
            _parting_rooms=set(),
            _pending_joins=[],
            _pending_parts=[],
            _events=queue.Queue(),
            _display_name="Moss",
            _identity=_FakeIdentity(),
            _destination=None,
            _welcome_timer=None,
        ), patch.object(rrc, "_send") as send:
            rrc._packet_received(
                rrc.cbor_encode({0: rrc.PROTOCOL_VERSION, 1: rrc.WELCOME, 5: {"0": "Test Hub"}}),
                link,
            )
            self.assertEqual(rrc._connection_state, "connected")
            due = [t for t in _FakeTimer.instances if not t.cancelled and not t.fired]
            self.assertEqual(len(due), 1, "WELCOME must schedule the keepalive")
            due[0].fire()
            send.assert_any_call(rrc.PING, body=unittest.mock.ANY)

    def test_incoming_ping_is_answered_with_matching_pong(self):
        _FakeTimer.instances = []
        link = _FakeLink()
        with patch.object(rrc.threading, "Timer", _FakeTimer), self._connected_state(link), patch.object(
            rrc, "_send"
        ) as send:
            payload = b"hub-keepalive-payload-12345678"
            rrc._packet_received(
                rrc.cbor_encode({0: rrc.PROTOCOL_VERSION, 1: rrc.PING, 5: "", 6: payload}),
                link,
            )
            send.assert_called_once_with(rrc.PONG, room="", body=payload)

    def test_incoming_ping_echoes_the_room_field(self):
        _FakeTimer.instances = []
        link = _FakeLink()
        with patch.object(rrc.threading, "Timer", _FakeTimer), self._connected_state(link), patch.object(
            rrc, "_send"
        ) as send:
            payload = b"hub-payload"
            rrc._packet_received(
                rrc.cbor_encode({0: rrc.PROTOCOL_VERSION, 1: rrc.PING, 5: "general", 6: payload}),
                link,
            )
            send.assert_called_once_with(rrc.PONG, room="general", body=payload)


if __name__ == "__main__":
    import sys

    unittest.main()
