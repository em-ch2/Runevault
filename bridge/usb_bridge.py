"""Forward newline-delimited Pico authorizations; the bridge never signs messages."""

import json
import os
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

import serial

MAX_LINE_BYTES = 4096
FIELDS = {"v", "device", "counter", "tx_id", "amount", "dest", "glyphs", "sig"}


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the bridge credential to a redirect target.
        return None


def messages(port):
    """Keep partial lines across serial timeouts; discard oversized frames to LF."""
    buffer = bytearray()
    discarding = False
    while True:
        chunk = port.read(1)
        if not chunk:  # Timeout is not the end of a JSON message.
            continue
        if chunk == b"\n":
            if buffer and not discarding:
                yield bytes(buffer)
            buffer.clear()
            discarding = False
        elif not discarding:
            buffer.extend(chunk)
            if len(buffer) > MAX_LINE_BYTES:
                print("Discarded oversized serial message.", file=sys.stderr, flush=True)
                buffer.clear()
                discarding = True


def parse_message(line):
    try:
        payload = json.loads(line.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise ValueError("Invalid UTF-8 JSON message") from None
    if not isinstance(payload, dict) or set(payload) != FIELDS:
        raise ValueError("Expected the eight Pico authorization fields")
    for name in ("v", "counter", "amount"):
        if type(payload[name]) is not int:
            raise ValueError(f"{name} must be an integer")
    for name in FIELDS - {"v", "counter", "amount"}:
        if not isinstance(payload[name], str):
            raise ValueError(f"{name} must be a string")
    return payload


def forward(payload, url, token, opener):
    request = Request(url, data=json.dumps(payload).encode("utf-8"), method="POST",
                      headers={"Content-Type": "application/json", "X-Bridge-Token": token})
    try:
        response = opener.open(request, timeout=10)
    except HTTPError as error:
        response = error  # Rejections have useful status codes and response bodies.
    except (URLError, TimeoutError, OSError):
        print("Backend connection failed; authorization outcome unknown. Not retrying automatically.",
              file=sys.stderr, flush=True)
        return
    try:
        with response:
            body = response.read(65537)
            text = body[:65536].decode("utf-8", errors="replace")
            try:
                result = json.loads(text)
            except ValueError:
                result = text
            print(json.dumps({"http_status": response.status,
                              "approved": 200 <= response.status < 300,
                              "response": result}), flush=True)
    except (TimeoutError, OSError):
        print("Backend response interrupted; authorization outcome unknown. Not retrying automatically.",
              file=sys.stderr, flush=True)


def main():
    token = os.environ.get("RUNEVAULT_BRIDGE_TOKEN", "")
    port_name = os.environ.get("RUNEVAULT_SERIAL_PORT", "")
    base = os.environ.get("RUNEVAULT_API_URL", "http://localhost:8080").rstrip("/")
    parsed = urlsplit(base)
    if not token or "\r" in token or "\n" in token or not port_name:
        print("Set RUNEVAULT_SERIAL_PORT and RUNEVAULT_BRIDGE_TOKEN in the environment.", file=sys.stderr)
        return 2
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        print("RUNEVAULT_API_URL must be an HTTP(S) base URL without credentials, query, or fragment.", file=sys.stderr)
        return 2
    try:
        baud = int(os.environ.get("RUNEVAULT_SERIAL_BAUD", "115200"))
        if baud <= 0:
            raise ValueError
    except ValueError:
        print("RUNEVAULT_SERIAL_BAUD must be a positive integer.", file=sys.stderr)
        return 2
    opener = build_opener(NoRedirects())
    url = base + "/api/device/authorize"
    try:
        while True:
            try:
                with serial.Serial(port_name, baudrate=baud, timeout=1) as port:
                    print(f"Connected to {port_name}; forwarding Pico authorizations to {url}", flush=True)
                    for line in messages(port):
                        try:
                            payload = parse_message(line)
                        except ValueError as error:
                            print(f"Skipped serial message: {error}", file=sys.stderr, flush=True)
                            continue
                        forward(payload, url, token, opener)
            except (serial.SerialException, OSError):
                print("Serial connection unavailable; reconnecting in 2 seconds.", file=sys.stderr, flush=True)
                time.sleep(2)
    except KeyboardInterrupt:
        print("Bridge stopped.", flush=True)
        return 0


if __name__ == "__main__":
    sys.exit(main())
