"""Bridge protocol regressions that do not require RNS, LXMF, or a network."""

import json
import os
import queue
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

import starling_bridge_protocol as protocol
import starling_ios_runtime as ios_runtime
import starling_rns as rns_bridge
import starling_rrc as rrc


class BridgeProtocolTest(unittest.TestCase):
    def test_reticulum_shutdown_clears_cached_state_even_when_exit_handler_fails(self):
        reticulum = Mock()
        reticulum.exit_handler.side_effect = RuntimeError("shutdown failed")
        rns = types.ModuleType("RNS")
        rns.Reticulum = reticulum
        previous_state = (
            rns_bridge._reticulum, rns_bridge._mode, rns_bridge._tcp_peers,
            rns_bridge._rnode_interfaces, rns_bridge._rpc_key_hex, rns_bridge._config_dir,
        )
        rns_bridge._reticulum = object()
        rns_bridge._mode = "embedded"
        rns_bridge._tcp_peers = [{"host": "localhost", "port": 4242}]
        rns_bridge._rnode_interfaces = [{"port": "radio"}]
        rns_bridge._rpc_key_hex = "private"
        rns_bridge._config_dir = "/private/data"
        try:
            with patch.dict(sys.modules, {"RNS": rns}):
                with self.assertRaisesRegex(RuntimeError, "shutdown failed"):
                    rns_bridge.stop_node()
            self.assertEqual(
                (rns_bridge._reticulum, rns_bridge._mode, rns_bridge._tcp_peers,
                 rns_bridge._rnode_interfaces, rns_bridge._rpc_key_hex, rns_bridge._config_dir),
                (None, None, [], [], None, None),
            )
            reticulum.exit_handler.assert_called_once_with()
        finally:
            (rns_bridge._reticulum, rns_bridge._mode, rns_bridge._tcp_peers,
             rns_bridge._rnode_interfaces, rns_bridge._rpc_key_hex, rns_bridge._config_dir) = previous_state

    def test_ios_ble_rnode_uses_native_tcp_proxy_and_preserves_configured_port(self):
        radios = [{
            "port": "ble://RNode Field Unit", "frequency_hz": 915000000,
            "bandwidth_hz": 125000, "tx_power_dbm": 17,
            "spreading_factor": 7, "coding_rate": 5,
        }]

        prepared = ios_runtime._prepare_rnode_interfaces(radios)

        self.assertEqual(prepared[0]["port"], "ble://RNode Field Unit")
        self.assertEqual(prepared[0]["runtime_port"], "tcp://127.0.0.1")
        self.assertNotIn("runtime_port", radios[0])

    def test_ios_ble_rnode_config_targets_the_loopback_proxy(self):
        radio = ios_runtime._prepare_rnode_interfaces([{
            "port": "ble://RNode Field Unit", "frequency_hz": 915000000,
            "bandwidth_hz": 125000, "tx_power_dbm": 17,
            "spreading_factor": 7, "coding_rate": 5,
        }])[0]
        created = []

        class ConfigObj(dict):
            def __init__(self):
                super().__init__()
                created.append(self)

            def write(self):
                pass

        rns = types.ModuleType("RNS")
        vendor = types.ModuleType("RNS.vendor")
        vendor.__path__ = []
        config_module = types.ModuleType("RNS.vendor.configobj")
        config_module.ConfigObj = ConfigObj
        with tempfile.TemporaryDirectory() as config_dir, patch.dict(sys.modules, {
            "RNS": rns,
            "RNS.vendor": vendor,
            "RNS.vendor.configobj": config_module,
        }):
            rns_bridge._write_config(config_dir, "embedded", [], [radio], None)

        configured_radio = created[0]["interfaces"]["Starling RNode 1"]
        self.assertEqual(configured_radio["port"], "tcp://127.0.0.1")
        self.assertEqual(radio["port"], "ble://RNode Field Unit")

    def test_android_rnode_config_uses_android_tcp_adapter_for_ble_and_wifi(self):
        created = []

        class ConfigObj(dict):
            def __init__(self):
                super().__init__()
                created.append(self)

            def write(self):
                pass

        rns = types.ModuleType("RNS")
        vendor = types.ModuleType("RNS.vendor")
        vendor.__path__ = []
        config_module = types.ModuleType("RNS.vendor.configobj")
        config_module.ConfigObj = ConfigObj
        radios = [
            {
                "port": "ble://RNode Field Unit", "runtime_port": "127.0.0.1",
                "frequency_hz": 915000000, "bandwidth_hz": 125000,
                "tx_power_dbm": 17, "spreading_factor": 7, "coding_rate": 5,
            },
            {
                "port": "tcp://192.168.1.25", "runtime_port": "192.168.1.25",
                "frequency_hz": 915000000, "bandwidth_hz": 125000,
                "tx_power_dbm": 17, "spreading_factor": 7, "coding_rate": 5,
            },
        ]

        with tempfile.TemporaryDirectory() as config_dir, patch.dict(sys.modules, {
            "RNS": rns,
            "RNS.vendor": vendor,
            "RNS.vendor.configobj": config_module,
        }):
            rns_bridge._write_config(config_dir, "embedded", [], radios, None, android_platform=True)

        interfaces = created[0]["interfaces"]
        ble_config = interfaces["Starling RNode 1"]
        wifi_config = interfaces["Starling RNode 2"]
        self.assertEqual(ble_config["type"], "RNodeInterface")
        self.assertEqual(ble_config["force_tcp"], "yes")
        self.assertEqual(ble_config["tcp_host"], "127.0.0.1")
        self.assertEqual(ble_config["allow_bluetooth"], "no")
        self.assertEqual(wifi_config["tcp_host"], "192.168.1.25")
        self.assertEqual(wifi_config["port"], "")

    def test_android_tcp_rnode_compat_modules_satisfy_rns_import_checks(self):
        with patch.dict(sys.modules):
            rns_bridge._install_android_tcp_rnode_compat()
            import importlib.util

            self.assertIsNotNone(importlib.util.find_spec("usbserial4a"))
            self.assertIsNotNone(importlib.util.find_spec("usbserial4a.serial4a"))
            self.assertIsNotNone(importlib.util.find_spec("jnius"))

    def test_rrc_roomless_notices_and_presence_events_keep_context(self):
        with patch.object(rrc, "_events", queue.Queue()):
            rrc._emit("message", message_id="id", room="", sender_id="hub", sender_name="Hub",
                      body="Command response", timestamp=123, is_notice=True)
            rrc._emit("member_joined", room="general", member_id="member", member_name="Moss",
                      lxmf_address="address", timestamp=124)
            rrc._emit("member_left", room="general", member_id="member", timestamp=125)
            rrc._emit("error", message="not authorized", room="general", timestamp=126)

            lines = rrc.poll_event_lines().splitlines()

        self.assertEqual(lines[0].split("\t")[2], "")
        self.assertEqual(lines[0].split("\t")[6:9], ["123", "0", "1"])
        self.assertEqual(lines[1].split("\t")[-1], "124")
        self.assertEqual(lines[2].split("\t")[-1], "125")
        self.assertEqual(lines[3].split("\t")[-1], "126")

    def test_rrc_join_and_part_share_utf8_room_name_validation(self):
        room = "🌍" * 16  # Exactly 64 UTF-8 bytes.
        with patch.multiple(
            rrc,
            _connection_state="connected",
            _joined_rooms=set(),
            _joining_rooms=set(),
            _parting_rooms=set(),
            _display_name="Moss",
        ), patch.object(rrc, "_send") as send:
            rrc.join("  " + room + "  ")
            rrc.part("  " + room + "  ")

            for invalid_room in ("", "  ", "🌍" * 17):
                for operation in (rrc.join, rrc.part):
                    with self.subTest(operation=operation.__name__, room=invalid_room):
                        with self.assertRaises(ValueError):
                            operation(invalid_room)

        self.assertEqual(send.call_args_list, [
            unittest.mock.call(rrc.JOIN, room=room, nickname="Moss"),
            unittest.mock.call(rrc.PART, room=room, nickname="Moss"),
        ])

    def test_rrc_joined_flushes_queued_room_messages_in_order(self):
        with patch.multiple(
            rrc,
            _connection_state="connected",
            _joining_rooms={"general"},
            _joined_rooms=set(),
            _pending_messages=[],
            _events=queue.Queue(),
            _display_name="Moss",
            _identity=None,
        ), patch.object(rrc, "cbor_decode", return_value={0: rrc.PROTOCOL_VERSION, 1: rrc.JOINED, 5: "General"}), \
                patch.object(rrc, "_send") as send:
            rrc.send("general", "first message")
            rrc._pending_messages.append(("other", "keep for other room", False))
            rrc.send("General", "second message", is_action=True)
            rrc._packet_received(b"", None)

            self.assertEqual(rrc._pending_messages, [("other", "keep for other room", False)])
            self.assertIn("general", rrc._joined_rooms)
            self.assertEqual(send.call_args_list, [
                unittest.mock.call(rrc.MSG, room="general", body="first message", nickname="Moss"),
                unittest.mock.call(rrc.ACTION, room="General", body="second message", nickname="Moss"),
            ])

    def test_rrc_pending_send_failures_are_reported_and_do_not_block_later_messages(self):
        events = queue.Queue()

        def send(message_type, **kwargs):
            if kwargs["body"] == "fail":
                raise RuntimeError("link send failed")

        with patch.multiple(
            rrc,
            _connection_state="connected",
            _joining_rooms={"general"},
            _joined_rooms=set(),
            _pending_messages=[("general", "fail", False), ("general", "still sent", False)],
            _events=events,
            _display_name="Moss",
            _identity=None,
        ), patch.object(rrc, "cbor_decode", return_value={0: rrc.PROTOCOL_VERSION, 1: rrc.JOINED, 5: "general"}), \
                patch.object(rrc, "_send", side_effect=send) as send_mock:
            rrc._packet_received(b"", None)

            self.assertEqual(send_mock.call_count, 2)
            errors = [event for event in list(events.queue) if event.get("kind") == "error"]
            self.assertEqual(len(errors), 1)
            self.assertEqual(errors[0]["room"], "general")
            self.assertIn("link send failed", errors[0]["message"])

    def test_rrc_welcome_reports_failed_pending_room_command_and_continues(self):
        events = queue.Queue()

        def send(message_type, **kwargs):
            if kwargs["room"] == "broken":
                raise RuntimeError("link send failed")

        with patch.multiple(
            rrc,
            _connection_state="connecting",
            _pending_joins=["broken", "general"],
            _pending_parts=["other"],
            _joining_rooms=set(),
            _parting_rooms=set(),
            _welcome_timer=None,
            _events=events,
            _display_name="Moss",
        ), patch.object(rrc, "cbor_decode", return_value={0: rrc.PROTOCOL_VERSION, 1: rrc.WELCOME, 6: {}}), \
                patch.object(rrc, "_send", side_effect=send) as send_mock:
            rrc._packet_received(b"", None)

            self.assertEqual(rrc._connection_state, "connected")
            self.assertEqual(rrc._pending_joins, [])
            self.assertEqual(rrc._pending_parts, [])
            self.assertNotIn("broken", rrc._joining_rooms)
            self.assertEqual(rrc._joining_rooms, {"general"})
            self.assertEqual(rrc._parting_rooms, {"other"})
            self.assertEqual(send_mock.call_args_list, [
                unittest.mock.call(rrc.JOIN, room="broken", nickname="Moss"),
                unittest.mock.call(rrc.JOIN, room="general", nickname="Moss"),
                unittest.mock.call(rrc.PART, room="other", nickname="Moss"),
            ])
            errors = [event for event in list(events.queue) if event.get("kind") == "error"]
            self.assertEqual(len(errors), 1)
            self.assertEqual(errors[0]["room"], "broken")
            self.assertIn("Could not join RRC room", errors[0]["message"])
            self.assertIn("link send failed", errors[0]["message"])

    def mixed_start_fields(self):
        return [
            "START", "EMBEDDED", protocol.encode(""), "4", "2", "1",
            protocol.encode("mesh.example"), "4242", protocol.encode("private mesh"), protocol.encode("passphrase"),
            protocol.encode("localhost"), "4243", "", "",
            protocol.encode("/dev/ttyUSB0"), "915000000", "125000", "17", "7", "5",
        ]

    def test_base64_fields_preserve_empty_strings_unicode_and_delimiters(self):
        for text, encoded in [("", ""), ("hello", "aGVsbG8="), ("🌍🌍", "8J+MjfCfjI0=")]:
            with self.subTest(text=text):
                self.assertEqual(protocol.encode(text), encoded)
                self.assertEqual(protocol.decode(encoded), text)
        text = "Hello 🌍\twith tabs\nand lines"
        self.assertEqual(protocol.decode(protocol.encode(text)), text)

    def test_base64_decoder_rejects_invalid_characters_padding_and_utf8(self):
        for value in ["%%%", "YQ", "/w=="]:
            with self.subTest(value=value), self.assertRaises((ValueError, UnicodeDecodeError)):
                protocol.decode(value)

    def test_start_request_parses_tcp_ifac_and_radio_fields(self):
        mode, key, peers, radios = protocol.parse_start_request(self.mixed_start_fields())
        self.assertEqual(mode, "embedded")
        self.assertEqual(key, "")
        self.assertEqual(peers, [
            {"host": "mesh.example", "port": 4242, "ifac_network_name": "private mesh", "ifac_passphrase": "passphrase"},
            {"host": "localhost", "port": 4243, "ifac_network_name": "", "ifac_passphrase": ""},
        ])
        self.assertEqual(radios, [{
            "port": "/dev/ttyUSB0", "frequency_hz": 915000000, "bandwidth_hz": 125000,
            "tx_power_dbm": 17, "spreading_factor": 7, "coding_rate": 5,
        }])

    def test_shared_instance_start_request_preserves_rpc_key_without_interfaces(self):
        key = "0123456789abcdef" * 2
        fields = ["START", "EXISTING_SHARED_INSTANCE", protocol.encode(key), "4", "0", "0"]
        self.assertEqual(protocol.parse_start_request(fields), ("existing_shared_instance", key, [], []))

    def test_start_request_rejects_unknown_runtime_mode(self):
        fields = ["START", "SHARED", "", "4", "0", "0"]
        with self.assertRaisesRegex(ValueError, "Unsupported Reticulum runtime mode"):
            protocol.parse_start_request(fields)

    def test_invalid_start_headers_versions_counts_and_field_lengths_are_rejected(self):
        base = ["START", "EMBEDDED", "", "4", "0", "0"]
        cases = [
            ([], "Invalid start request."),
            (["UNKNOWN"] + base[1:], "Invalid start request."),
            (base[:3] + ["3"] + base[4:], "Unsupported desktop bridge protocol version."),
            (base[:4] + ["invalid", "0"], "Invalid interface count."),
            (base[:4] + ["0", "invalid"], "Invalid interface count."),
            (base[:4] + ["-1", "0"], "Interface configuration data is incomplete."),
            (base[:4] + ["0", "-1"], "Interface configuration data is incomplete."),
            (base[:4] + ["1", "0"], "Interface configuration data is incomplete."),
            (base + ["extra"], "Interface configuration data is incomplete."),
        ]
        for fields, message in cases:
            with self.subTest(fields=fields):
                with self.assertRaises(ValueError) as error:
                    protocol.parse_start_request(fields)
                self.assertEqual(str(error.exception), message)
        with self.assertRaisesRegex(ValueError, "Unsupported iOS bridge protocol version"):
            protocol.parse_start_request(base[:3] + ["3"] + base[4:], platform="iOS")

    def test_invalid_numeric_interface_fields_are_rejected(self):
        for index in [7, 11, 15, 16, 17, 18, 19]:
            fields = self.mixed_start_fields()
            fields[index] = "invalid"
            with self.subTest(index=index), self.assertRaises(ValueError):
                protocol.parse_start_request(fields)

    def test_status_wire_format_preserves_nested_base64_and_online_flags(self):
        node = Mock()
        node.status.return_value = json.dumps({
            "running": True,
            "mode": "EMBEDDED",
            "shared_instance_connected": True,
            "tcp_interfaces": [{"host": "mesh.example", "port": 4242, "online": True}],
            "rnode_interfaces": [{"port": "COM3", "online": False}],
        })
        self.assertEqual(protocol.status_line(node), "\t".join([
            "STATUS", "RUNNING", "EMBEDDED",
            protocol.encode(protocol.encode("mesh.example") + ",4242,1"), "1", "1",
            protocol.encode(protocol.encode("COM3") + ",0"),
        ]))
        node.status.return_value = json.dumps({"running": False})
        self.assertEqual(protocol.status_line(node), "STATUS\tSTOPPED")

    def test_rrc_dispatch_preserves_decoding_and_action_flags(self):
        rrc = Mock()
        body = "Hello 🌍\twith tabs\nand lines"
        protocol.dispatch_rrc("RRC_CONNECT\t{}\t{}".format(protocol.encode("hub"), protocol.encode("Mesh 🌍")), rrc)
        protocol.dispatch_rrc("RRC_JOIN\t" + protocol.encode("general"), rrc)
        protocol.dispatch_rrc("RRC_PART\t" + protocol.encode("general"), rrc)
        protocol.dispatch_rrc("RRC_SEND\t{}\t{}\t1".format(protocol.encode("general"), protocol.encode(body)), rrc)
        protocol.dispatch_rrc("RRC_SEND\t{}\t{}\t0".format(protocol.encode("general"), protocol.encode(body)), rrc)
        protocol.dispatch_rrc("RRC_DISCONNECT", rrc)
        rrc.connect.assert_called_once_with("hub", "Mesh 🌍")
        rrc.join.assert_called_once_with("general")
        rrc.part.assert_called_once_with("general")
        self.assertEqual(rrc.send.call_args_list, [
            unittest.mock.call("general", body, True),
            unittest.mock.call("general", body, False),
        ])
        rrc.disconnect.assert_called_once_with()
        for command in ["UNKNOWN", "RRC_CONNECT", "RRC_SEND\tZg==",
                        "RRC_SEND\tZg==\tYQ==\ttrue", "RRC_SEND\tZg==\tYQ==\t2",
                        "RRC_DISCONNECT\tunexpected"]:
            with self.subTest(command=command), self.assertRaises(ValueError):
                protocol.dispatch_rrc(command, rrc)
        rrc.disconnect.assert_called_once_with()

    def test_lxmf_dispatch_preserves_empty_names_and_message_body(self):
        lxmf = Mock()
        protocol.dispatch_lxmf("LXMF_SET_NAME\t", lxmf)
        protocol.dispatch_lxmf("LXMF_SEND\t{}\t{}".format(protocol.encode("contact"), protocol.encode("Hello 🌍")), lxmf)
        lxmf.set_display_name.assert_called_once_with("")
        lxmf.send_direct.assert_called_once_with("contact", "Hello 🌍")
        for command in ["UNKNOWN", "LXMF_SET_NAME", "LXMF_SEND\tZg=="]:
            with self.subTest(command=command), self.assertRaises(ValueError):
                protocol.dispatch_lxmf(command, lxmf)

    def fake_runtime_modules(self):
        rns, rrc, lxmf = Mock(), Mock(), Mock()
        rns.start_node.return_value = json.dumps({"running": True})
        rns.status.return_value = json.dumps({"running": True, "mode": "EMBEDDED"})
        rrc.poll_event_lines.return_value = lxmf.poll_event_lines.return_value = ""
        rrc.current_connection_event_line.return_value = rrc.current_self_event_line.return_value = None
        lxmf.current_local_address_event_line.return_value = lxmf.current_identity_event_line.return_value = None
        return rns, rrc, lxmf

    def test_ios_adapter_uses_shared_start_fields_and_preserves_reconfiguration_and_shutdown(self):
        rns, rrc, lxmf = self.fake_runtime_modules()
        fields = self.mixed_start_fields()
        mode, key, peers, radios = protocol.parse_start_request(fields)
        command = "\t".join(["IOS_START", protocol.encode("/private/data 🌍")] + fields[1:])
        with patch.dict(sys.modules, {"starling_rns": rns, "starling_rrc": rrc, "starling_lxmf": lxmf}), \
                patch.multiple(ios_runtime, _rns=None, _rrc=None, _lxmf=None, _last_self_event=None):
            self.assertTrue(ios_runtime.invoke(command).startswith("READY\tRUNNING\t"))
            rns.start_node.assert_called_once_with("/private/data 🌍", mode, peers, key, radios, allow_reconfigure=True)
            self.assertEqual(ios_runtime.invoke("STOP"), "STOPPED")
            rns.disconnect_node.assert_called_once_with()
            rns.stop_node.assert_not_called()

    def test_ios_adapter_reports_invalid_start_requests_before_starting_runtime(self):
        rns, rrc, lxmf = self.fake_runtime_modules()
        command = "\t".join(["IOS_START", protocol.encode("/private/data"), "EMBEDDED", "", "3", "0", "0"])
        with patch.dict(sys.modules, {"starling_rns": rns, "starling_rrc": rrc, "starling_lxmf": lxmf}):
            response = ios_runtime.invoke(command)
        self.assertEqual(response, "ERROR\t" + protocol.encode("Unsupported iOS bridge protocol version."))
        rns.start_node.assert_not_called()

    def test_ios_control_commands_reject_unexpected_fields_without_running(self):
        rns, rrc, lxmf = self.fake_runtime_modules()
        with patch.dict(sys.modules, {"starling_rns": rns, "starling_rrc": rrc, "starling_lxmf": lxmf}), \
                patch.multiple(ios_runtime, _rns=rns, _rrc=rrc, _lxmf=lxmf):
            for command in ("STATUS\textra", "POLL\textra", "STOP\textra"):
                with self.subTest(command=command):
                    response = ios_runtime.invoke(command)
                    self.assertTrue(response.startswith("EVENT\tERROR\t"))
                    self.assertEqual(protocol.decode(response.split("\t")[2]),
                                     command.split("\t")[0] + " does not accept arguments.")

        rns.status.assert_not_called()
        rrc.poll_event_lines.assert_not_called()
        rrc.disconnect.assert_not_called()
        lxmf.stop.assert_not_called()
        rns.disconnect_node.assert_not_called()

    def test_ios_adapter_keeps_lxmf_command_and_poll_errors_out_of_rrc_events(self):
        rns, rrc, lxmf = self.fake_runtime_modules()
        lxmf.send_direct.side_effect = RuntimeError("LXMF send failed")
        lxmf.poll_event_lines.side_effect = RuntimeError("LXMF event poll failed")
        with patch.dict(sys.modules, {"starling_rns": rns, "starling_rrc": rrc, "starling_lxmf": lxmf}), \
                patch.multiple(ios_runtime, _rns=rns, _rrc=rrc, _lxmf=lxmf):
            command_error = ios_runtime.invoke("LXMF_SEND\tYWRkcmVzcw==\taGVsbG8=")
            poll_errors = ios_runtime.invoke("POLL").splitlines()

        self.assertTrue(command_error.startswith("EVENT\tLXMF_ERROR\t"))
        self.assertEqual(protocol.decode(command_error.split("\t")[2]), "LXMF send failed")
        self.assertTrue(any(line.startswith("EVENT\tLXMF_ERROR\t") for line in poll_errors))
        self.assertFalse(any(line.startswith("EVENT\tERROR\t") for line in poll_errors))

    def test_ios_startup_failure_cleans_up_partial_runtime_and_preserves_start_error(self):
        rns, rrc, lxmf = self.fake_runtime_modules()
        rns.start_node.side_effect = RuntimeError("radio initialization failed")
        rrc.disconnect.side_effect = RuntimeError("room cleanup failed")
        lxmf.stop.side_effect = RuntimeError("LXMF cleanup failed")
        command = "\t".join(["IOS_START", protocol.encode("/private/data")] + self.mixed_start_fields()[1:])
        with patch.dict(sys.modules, {"starling_rns": rns, "starling_rrc": rrc, "starling_lxmf": lxmf}), \
                patch.multiple(ios_runtime, _rns=None, _rrc=None, _lxmf=None, _last_self_event="stale"):
            response = ios_runtime.invoke(command)
            self.assertEqual(response, "ERROR\t" + protocol.encode("radio initialization failed"))
            self.assertIsNone(ios_runtime._rns)
            self.assertIsNone(ios_runtime._rrc)
            self.assertIsNone(ios_runtime._lxmf)
            self.assertIsNone(ios_runtime._last_self_event)

        rrc.disconnect.assert_called_once_with()
        lxmf.stop.assert_called_once_with()
        rns.disconnect_node.assert_called_once_with()

    def test_ios_shutdown_attempts_every_component_after_an_earlier_failure(self):
        rns, rrc, lxmf = self.fake_runtime_modules()
        rrc.disconnect.side_effect = RuntimeError("room close failed")
        lxmf.stop.side_effect = RuntimeError("LXMF close failed")
        with patch.dict(sys.modules, {"starling_rns": rns, "starling_rrc": rrc, "starling_lxmf": lxmf}), \
                patch.multiple(ios_runtime, _rns=rns, _rrc=rrc, _lxmf=lxmf, _last_self_event="stale"):
            response = ios_runtime.invoke("STOP")

        self.assertTrue(response.endswith("STOPPED"))
        self.assertIn("EVENT\tERROR\t" + protocol.encode("RRC shutdown failed: room close failed"), response)
        self.assertIn("EVENT\tLXMF_ERROR\t" + protocol.encode("LXMF shutdown failed: LXMF close failed"), response)
        rns.disconnect_node.assert_called_once_with()
        self.assertIsNone(ios_runtime._rns)
        self.assertIsNone(ios_runtime._rrc)
        self.assertIsNone(ios_runtime._lxmf)
        self.assertIsNone(ios_runtime._last_self_event)

    def test_desktop_adapter_preserves_trailing_empty_start_and_command_fields(self):
        script = """
import json, sys
from unittest.mock import Mock
rns, rrc, lxmf = Mock(), Mock(), Mock()
rns.status.return_value = json.dumps({"running": True, "mode": "EMBEDDED"})
rrc.poll_event_lines.return_value = lxmf.poll_event_lines.return_value = ""
rrc.disconnect.side_effect = RuntimeError("room close failed")
lxmf.stop.side_effect = RuntimeError("LXMF close failed")
lxmf.send_direct.side_effect = RuntimeError("LXMF send failed")
sys.modules.update(starling_rns=rns, starling_rrc=rrc, starling_lxmf=lxmf)
import starling_rns_service
code = starling_rns_service.main()
rns.start_node.assert_called_once_with(sys.argv[1], "embedded", [
    {"host": "localhost", "port": 4242, "ifac_network_name": "", "ifac_passphrase": ""},
], "", [])
lxmf.set_display_name.assert_called_once_with("")
rns.stop_node.assert_called_once_with()
rrc.disconnect.assert_called_once_with()
lxmf.stop.assert_called_once_with()
raise SystemExit(code)
"""
        command = "\t".join(["START", "EMBEDDED", "", "4", "1", "0", protocol.encode("localhost"), "4242", "", ""])
        environment = os.environ.copy()
        environment["PYTHONPATH"] = os.path.dirname(protocol.__file__)
        with tempfile.TemporaryDirectory(prefix="starling-bridge-test-") as data_dir:
            result = subprocess.run(
                [sys.executable, "-c", script, data_dir], input=command + "\r\nLXMF_SET_NAME\t\nLXMF_SEND\tYWRkcmVzcw==\taGVsbG8=\nSTOP\n",
                text=True, capture_output=True, env=environment, timeout=10,
            )
            with open(os.path.join(data_dir, "starling-rns.log"), encoding="utf-8") as log:
                diagnostics = result.stdout + result.stderr + log.read()
        self.assertEqual(result.returncode, 0, diagnostics)
        self.assertTrue(result.stdout.startswith("READY\tRUNNING\t"), diagnostics)
        bridge_events = [line.split("\t") for line in result.stdout.splitlines() if line.startswith("EVENT\t")]
        self.assertEqual([(fields[1], protocol.decode(fields[2])) for fields in bridge_events], [
            ("LXMF_ERROR", "LXMF send failed"),
            ("ERROR", "RRC shutdown failed: room close failed"),
            ("LXMF_ERROR", "LXMF shutdown failed: LXMF close failed"),
        ], diagnostics)
        self.assertTrue(result.stdout.endswith("STOPPED\n"), diagnostics)

    def test_desktop_startup_failure_cleans_up_initialized_runtime_modules(self):
        script = """
import sys
from unittest.mock import Mock
rns, rrc, lxmf = Mock(), Mock(), Mock()
rns.start_node.side_effect = RuntimeError("interface initialization failed")
sys.modules.update(starling_rns=rns, starling_rrc=rrc, starling_lxmf=lxmf)
import starling_rns_service
code = starling_rns_service.main()
rns.stop_node.assert_called_once_with()
rrc.disconnect.assert_called_once_with()
lxmf.stop.assert_called_once_with()
raise SystemExit(code)
"""
        command = "\t".join(["START", "EMBEDDED", "", "4", "0", "0"])
        environment = os.environ.copy()
        environment["PYTHONPATH"] = os.path.dirname(protocol.__file__)
        with tempfile.TemporaryDirectory(prefix="starling-bridge-test-") as data_dir:
            result = subprocess.run(
                [sys.executable, "-c", script, data_dir], input=command + "\n",
                text=True, capture_output=True, env=environment, timeout=10,
            )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        fields = result.stdout.strip().split("\t")
        self.assertEqual(fields[:1], ["ERROR"])
        self.assertEqual(protocol.decode(fields[1]), "interface initialization failed")


if __name__ == "__main__":
    unittest.main()
