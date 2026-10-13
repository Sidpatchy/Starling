"""Bridge protocol regressions that do not require RNS, LXMF, or a network."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import starling_bridge_protocol as protocol
import starling_ios_runtime as ios_runtime


class BridgeProtocolTest(unittest.TestCase):
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
        protocol.dispatch_rrc("RRC_DISCONNECT", rrc)
        rrc.connect.assert_called_once_with("hub", "Mesh 🌍")
        rrc.join.assert_called_once_with("general")
        rrc.part.assert_called_once_with("general")
        rrc.send.assert_called_once_with("general", body, True)
        rrc.disconnect.assert_called_once_with()
        for command in ["UNKNOWN", "RRC_CONNECT", "RRC_SEND\tZg=="]:
            with self.subTest(command=command), self.assertRaises(ValueError):
                protocol.dispatch_rrc(command, rrc)

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

    def test_desktop_adapter_preserves_trailing_empty_start_and_command_fields(self):
        script = """
import json, sys
from unittest.mock import Mock
rns, rrc, lxmf = Mock(), Mock(), Mock()
rns.status.return_value = json.dumps({"running": True, "mode": "EMBEDDED"})
rrc.poll_event_lines.return_value = lxmf.poll_event_lines.return_value = ""
sys.modules.update(starling_rns=rns, starling_rrc=rrc, starling_lxmf=lxmf)
import starling_rns_service
code = starling_rns_service.main()
rns.start_node.assert_called_once_with(sys.argv[1], "embedded", [
    {"host": "localhost", "port": 4242, "ifac_network_name": "", "ifac_passphrase": ""},
], "", [])
lxmf.set_display_name.assert_called_once_with("")
rns.stop_node.assert_called_once_with()
raise SystemExit(code)
"""
        command = "\t".join(["START", "EMBEDDED", "", "4", "1", "0", protocol.encode("localhost"), "4242", "", ""])
        environment = os.environ.copy()
        environment["PYTHONPATH"] = os.path.dirname(protocol.__file__)
        with tempfile.TemporaryDirectory(prefix="starling-bridge-test-") as data_dir:
            result = subprocess.run(
                [sys.executable, "-c", script, data_dir], input=command + "\r\nLXMF_SET_NAME\t\nSTOP\n",
                text=True, capture_output=True, env=environment, timeout=10,
            )
            with open(os.path.join(data_dir, "starling-rns.log"), encoding="utf-8") as log:
                diagnostics = result.stdout + result.stderr + log.read()
        self.assertEqual(result.returncode, 0, diagnostics)
        self.assertTrue(result.stdout.startswith("READY\tRUNNING\t"), diagnostics)
        self.assertTrue(result.stdout.endswith("STOPPED\n"), diagnostics)


if __name__ == "__main__":
    unittest.main()
