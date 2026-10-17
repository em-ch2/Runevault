import contextlib
import io
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import Mock, patch
from urllib.error import URLError
from urllib.request import build_opener

import usb_bridge


class BridgeTest(unittest.TestCase):
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
        with patch.dict("os.environ", {}, clear=True), patch("usb_bridge.serial.Serial") as serial_port:
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(usb_bridge.main(), 2)
            serial_port.assert_not_called()


if __name__ == "__main__":
    unittest.main()
