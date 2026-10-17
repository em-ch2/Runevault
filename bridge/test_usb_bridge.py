import contextlib
import io
import json
import threading
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import MagicMock, Mock, patch
from urllib.error import URLError
from urllib.error import HTTPError
from urllib.request import build_opener

import usb_bridge


class BridgeTest(unittest.TestCase):
    def test_existing_transaction_uses_stored_amount_and_destination(self):
        port = Mock()
        command = b"TX|abc123|50000|acct-999\n"
        port.write.return_value = len(command)
        response = MagicMock()
        response.status = 200
        response.read.return_value = json.dumps({"tx_id": "abc123", "device": "slab-01",
                                                  "status": "PENDING", "amount": 50000,
                                                  "dest": "acct-999", "expires_at": 9999999999}).encode()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = response
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(usb_bridge.send_existing(port, "http://localhost:8080", "secret",
                                                       opener, "abc123", "slab-01"), "abc123")
        self.assertEqual(opener.open.call_args.args[0].get_header("X-bridge-token"), "secret")
        self.assertIn("/api/device/transactions/abc123?device=slab-01", opener.open.call_args.args[0].full_url)
        port.write.assert_called_once_with(command)

    def test_existing_transaction_rejects_mismatch_and_expiry_before_serial_write(self):
        for change in ({"device": "other"}, {"status": "SIMULATED"},
                       {"expires_at": 1}, {"amount": "50000"}):
            port = Mock()
            response = MagicMock()
            response.status = 200
            details = {"tx_id": "abc123", "device": "slab-01", "status": "PENDING",
                       "amount": 50000, "dest": "acct-999", "expires_at": 9999999999}
            details.update(change)
            response.read.return_value = json.dumps(details).encode()
            response.__enter__ = Mock(return_value=response)
            response.__exit__ = Mock(return_value=False)
            opener = Mock()
            opener.open.return_value = response
            with self.assertRaises(ValueError):
                usb_bridge.send_existing(port, "http://localhost:8080", "secret", opener, "abc123", "slab-01")
            port.write.assert_not_called()

    def test_existing_lookup_rejection_never_writes_to_pico(self):
        port = Mock()
        opener = Mock()
        error = HTTPError("http://localhost", 404, "missing", {}, io.BytesIO(b"{}"))
        opener.open.side_effect = error
        with self.assertRaisesRegex(ValueError, "HTTP 404"):
            usb_bridge.send_existing(port, "http://localhost:8080", "secret", opener, "abc123", "slab-01")
        port.write.assert_not_called()

    def test_firmware_acknowledges_matching_transaction(self):
        port = Mock()
        port.read.side_effect = [bytes([byte]) for byte in
                                 b"# ready\r\n# enter glyphs for tx abc123\r\n"]
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertTrue(usb_bridge.wait_for_pico_ack(port, "abc123"))
        self.assertIn("Pico: # enter glyphs for tx abc123", output.getvalue())

    def test_wrong_firmware_acknowledgment_does_not_start_buttons(self):
        port = Mock()
        port.read.side_effect = [bytes([byte]) for byte in b"# enter glyphs for tx other\n"]
        with patch("usb_bridge.time.monotonic", side_effect=[0, 0, 0, 0, 0, 9]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertFalse(usb_bridge.wait_for_pico_ack(port, "abc123"))

    def test_local_compose_token_takes_precedence_without_printing_it(self):
        with TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            env_file.write_text('RUNEVAULT_BRIDGE_TOKEN="local-secret"\n', encoding="utf-8")
            with patch("usb_bridge.LOCAL_ENV", env_file), patch.dict("os.environ", {"RUNEVAULT_BRIDGE_TOKEN": "stale"}):
                self.assertEqual(usb_bridge.bridge_token(), "local-secret")

    def test_pending_transaction_uses_firmware_command(self):
        port = Mock()
        command = b"TX|abc123|50000|acct-999\n"
        port.write.return_value = len(command)
        with patch("usb_bridge.forward", return_value=(201, {"tx_id": "abc123", "status": "PENDING"})) as post:
            with contextlib.redirect_stdout(io.StringIO()):
                result = usb_bridge.send_pending(port, "http://localhost:8080", "test-token", Mock(),
                                                 "slab-01", 50000, "acct-999", "0-2-1-2-1")
        self.assertEqual(result, "abc123")
        port.write.assert_called_once_with(command)
        port.flush.assert_called_once()
        self.assertEqual(post.call_args.args[0], {"device": "slab-01", "amount": 50000,
                                                "dest": "acct-999", "glyphs": "0-2-1-2-1"})
        self.assertEqual(post.call_args.args[1], "http://localhost:8080/api/device/transactions")

    def test_failed_creation_or_malformed_id_never_reaches_pico(self):
        for result in [None, (423, {"error": "locked"}), (201, {"status": "PENDING", "tx_id": "bad\nTX|id"})]:
            port = Mock()
            with patch("usb_bridge.forward", return_value=result), self.assertRaises(ValueError):
                usb_bridge.send_pending(port, "http://localhost:8080", "test-token", Mock(),
                                        "slab-01", 50000, "acct-999", "0-2-1-2-1")
            port.write.assert_not_called()

    def test_partial_serial_write_is_not_retried(self):
        port = Mock()
        port.write.return_value = 1
        with patch("usb_bridge.forward", return_value=(201, {"status": "PENDING", "tx_id": "abc123"})):
            with self.assertRaises(OSError):
                usb_bridge.send_pending(port, "http://localhost:8080", "test-token", Mock(),
                                        "slab-01", 50000, "acct-999", "0-2-1-2-1")
        port.write.assert_called_once()

    def test_serial_timeouts_and_oversized_frames(self):
        port = Mock()
        chunks = [b"{"] + [b""] * 2 + [b"}", b"\n"]
        chunks += [b"x"] * (usb_bridge.MAX_LINE_BYTES + 1) + [b"\n", b"{", b"}", b"\r", b"\n"]
        port.read.side_effect = chunks
        with contextlib.redirect_stderr(io.StringIO()):
            frames = usb_bridge.messages(port)
            self.assertEqual(next(frames), b"{}")
            self.assertEqual(next(frames), b"{}\r")

    def test_payload_validation(self):
        for raw in [b"not json", b"[]", b"{}", b"\xff"]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                usb_bridge.parse_message(raw)

    def test_http_forwarding_and_rejection(self):
        received = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received.append((self.path, self.headers.get("X-Bridge-Token"),
                                 json.loads(self.rfile.read(int(self.headers["Content-Length"])))))
                code = 200 if len(received) == 1 else 423
                self.send_response(code)
                self.end_headers()
                self.wfile.write(json.dumps({"status": "SIMULATED"} if code == 200 else {"error": "Device locked"}).encode())

            def log_message(self, *args):
                pass

        payload = {"v": 1, "device": "slab-01", "counter": 2, "tx_id": "abc123",
                   "amount": 50000, "dest": "acct-999", "glyphs": "0-2-1-2-1", "sig": "0" * 64}
        self.assertEqual(usb_bridge.parse_message(json.dumps(payload).encode()), payload)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                for _ in range(2):
                    usb_bridge.forward(payload, f"http://127.0.0.1:{server.server_port}/api/device/authorize",
                                       "test-token", build_opener(usb_bridge.NoRedirects()))
            responses = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertTrue(responses[0]["approved"])
            self.assertEqual(responses[1]["http_status"], 423)
            self.assertFalse(responses[1]["approved"])
            self.assertEqual(received, [("/api/device/authorize", "test-token", payload)] * 2)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()

    def test_network_failure_is_not_retried(self):
        opener = Mock()
        opener.open.side_effect = URLError("connection lost")
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            usb_bridge.forward({}, "http://localhost:8080/api/device/authorize", "test-token", opener)
        opener.open.assert_called_once()
        self.assertIn("outcome unknown", errors.getvalue())

    def test_redirects_are_not_followed(self):
        self.assertIsNone(usb_bridge.NoRedirects().redirect_request(None, None, 302, "", {}, "https://elsewhere/"))

    def test_missing_configuration_exits_before_opening_serial(self):
        with patch.dict("os.environ", {}, clear=True), patch("usb_bridge.LOCAL_ENV", Path("missing.env")), patch("usb_bridge.serial.Serial") as serial_port:
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(usb_bridge.main(), 2)
            serial_port.assert_not_called()

    def test_existing_mode_disallows_amount_and_destination_overrides(self):
        with patch("usb_bridge.bridge_token", return_value="test-token"), patch.dict(
                "os.environ", {"RUNEVAULT_SERIAL_PORT": "COM3"}), patch("usb_bridge.serial.Serial") as serial_port:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as exit_status:
                usb_bridge.main(["--tx-id", "abc123", "--amount", "50001", "--dest", "other"])
            self.assertEqual(exit_status.exception.code, 2)
            serial_port.assert_not_called()


if __name__ == "__main__":
    unittest.main()
