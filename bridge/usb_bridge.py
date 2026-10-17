"""Forward newline-delimited Pico authorizations; the bridge never signs messages."""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

import serial

MAX_LINE_BYTES = 4096
FIELDS = {"v", "device", "counter", "tx_id", "amount", "dest", "glyphs", "sig"}
LOCAL_ENV = Path(__file__).resolve().parent.parent / ".env"


def bridge_token():
    """Use the local Compose token when available; never display its value."""
    try:
        with LOCAL_ENV.open(encoding="utf-8-sig") as env_file:
            for line in env_file:
                name, separator, value = line.strip().partition("=")
                if separator and name == "RUNEVAULT_BRIDGE_TOKEN":
                    value = value.strip().strip('"').strip("'")
                    if value:
                        return value
    except FileNotFoundError:
        pass
    return os.environ.get("RUNEVAULT_BRIDGE_TOKEN", "")


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


def forward(payload, url, token, opener, *, pending=False):
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
                              "created" if pending else "approved": 200 <= response.status < 300,
                              "response": result}), flush=True)
            return response.status, result
    except (TimeoutError, OSError):
        print("Backend response interrupted; authorization outcome unknown. Not retrying automatically.",
              file=sys.stderr, flush=True)


def send_pending(port, base, token, opener, device, amount, dest, glyphs):
    result = forward({"device": device, "amount": amount, "dest": dest, "glyphs": glyphs},
                     base + "/api/device/transactions", token, opener, pending=True)
    if result is None or result[0] != 201:
        raise ValueError("Pending transaction was not created; nothing sent to Pico")
    body = result[1]
    if (not isinstance(body, dict) or body.get("status") != "PENDING"
            or not isinstance(body.get("tx_id"), str)
            or re.fullmatch(r"[A-Za-z0-9_-]{1,36}", body["tx_id"]) is None):
        raise ValueError("Invalid pending transaction response; nothing sent to Pico")
    command = f"TX|{body['tx_id']}|{amount}|{dest}\n".encode("ascii")
    if port.write(command) != len(command):
        raise OSError("Incomplete serial write")
    port.flush()
    print(f"Sent transaction {body['tx_id']} to Pico. Enter {glyphs}, then briefly press Enter. "
          "Complete within 120 seconds.", flush=True)
    return body["tx_id"]


def send_existing(port, base, token, opener, transaction_id, device):
    url = base + "/api/device/transactions/" + quote(transaction_id, safe="") + "?device=" + quote(device, safe="")
    request = Request(url, headers={"X-Bridge-Token": token}, method="GET")
    try:
        response = opener.open(request, timeout=10)
    except HTTPError as error:
        with error:
            error.read(65536)
        raise ValueError(f"Transaction lookup rejected (HTTP {error.code}); nothing sent to Pico") from None
    except (URLError, TimeoutError, OSError):
        raise ValueError("Transaction lookup failed; nothing sent to Pico") from None
    try:
        with response:
            if response.status != 200:
                raise ValueError("Transaction lookup failed; nothing sent to Pico")
            details = json.loads(response.read(4097).decode("utf-8"))
    except (ValueError, UnicodeDecodeError, TimeoutError, OSError):
        raise ValueError("Invalid transaction lookup response; nothing sent to Pico") from None
    if (not isinstance(details, dict) or details.get("tx_id") != transaction_id
            or details.get("device") != device or details.get("status") != "PENDING"
            or type(details.get("amount")) is not int or not 1 <= details["amount"] <= 100000000
            or not isinstance(details.get("dest"), str)
            or re.fullmatch(r"[A-Za-z0-9_-]{1,64}", details["dest"]) is None
            or type(details.get("expires_at")) is not int
            or details["expires_at"] <= time.time()):
        raise ValueError("Transaction is not a valid pending assignment; nothing sent to Pico")
    command = f"TX|{transaction_id}|{details['amount']}|{details['dest']}\n".encode("ascii")
    if port.write(command) != len(command):
        raise OSError("Incomplete serial write")
    port.flush()
    print(f"Sent existing transaction {transaction_id} to Pico. Enter its assigned glyph sequence, "
          "then briefly press Enter.", flush=True)
    return transaction_id


def wait_for_pico_ack(port, transaction_id, seconds=8):
    """Confirm the firmware accepted TX before asking for button presses."""
    deadline = time.monotonic() + seconds
    expected = f"# enter glyphs for tx {transaction_id}".encode("ascii")
    buffer = bytearray()
    while time.monotonic() < deadline:
        byte = port.read(1)
        if byte == b"\n":
            line = bytes(buffer).strip()
            buffer.clear()
            if line.startswith(b"#"):
                print("Pico: " + line.decode("utf-8", errors="replace"), flush=True)
            if line == expected:
                return True
        elif byte:
            buffer.extend(byte)
            if len(buffer) > MAX_LINE_BYTES:
                buffer.clear()
    return False


def main(argv=None):
    token = bridge_token()
    port_name = os.environ.get("RUNEVAULT_SERIAL_PORT", "")
    base = os.environ.get("RUNEVAULT_API_URL", "http://localhost:8080").rstrip("/")
    parsed = urlsplit(base)
    if not token or "\r" in token or "\n" in token or not port_name:
        print("Set RUNEVAULT_SERIAL_PORT and put RUNEVAULT_BRIDGE_TOKEN in .env or the environment.", file=sys.stderr)
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--amount", type=int, help="Create and send one transaction, amount in cents")
    parser.add_argument("--dest", help="Destination for the new transaction")
    parser.add_argument("--glyphs", help="Expected sequence, e.g. 0-2-1-2-1")
    parser.add_argument("--tx-id", help="Load an existing pending transaction; cannot override amount or destination")
    parser.add_argument("--device", default="slab-01")
    args = parser.parse_args(argv)
    creating = any(value is not None for value in (args.amount, args.dest, args.glyphs))
    if args.tx_id is not None and (creating or re.fullmatch(r"[A-Za-z0-9_-]{1,36}", args.tx_id) is None):
        parser.error("--tx-id requires a valid ID and cannot be combined with --amount, --dest, or --glyphs")
    if re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.device) is None:
        parser.error("--device must be a valid device ID")
    if creating and (args.amount is None or not 1 <= args.amount <= 100000000
                     or re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.dest or "") is None
                     or re.fullmatch(r"[0-2](-[0-2]){0,63}", args.glyphs or "") is None
                     or re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.device) is None):
        parser.error("Supply valid --amount (cents), --dest, and --glyphs together")
    opener = build_opener(NoRedirects())
    url = base + "/api/device/authorize"
    transaction_id = None
    creation_attempted = False
    try:
        while True:
            try:
                with serial.Serial(port_name, baudrate=baud, timeout=1, write_timeout=5) as port:
                    print(f"Connected to {port_name}; forwarding Pico authorizations to {url}", flush=True)
                    if creating or args.tx_id is not None:
                        input("Ensure the Pico firmware is running and waiting for TX. Press Enter here when ready: ")
                        creation_attempted = True
                        try:
                            if creating:
                                transaction_id = send_pending(port, base, token, opener, args.device,
                                                              args.amount, args.dest, args.glyphs)
                            else:
                                transaction_id = send_existing(port, base, token, opener, args.tx_id, args.device)
                        except ValueError as error:
                            print(str(error), file=sys.stderr, flush=True)
                            return 1
                        if not wait_for_pico_ack(port, transaction_id):
                            print("No Pico acknowledgment after TX. Check that the supplied firmware is running "
                                  "on this serial port and is waiting at its TX input loop. "
                                  "This pending transaction will expire; restart the bridge after fixing the Pico.",
                                  file=sys.stderr, flush=True)
                            return 1
                        sequence = args.glyphs if creating else "the sequence assigned to this transaction"
                        print(f"Pico accepted TX. Press glyphs {sequence}, then briefly press its GP2 Enter button.",
                              flush=True)
                    for line in messages(port):
                        if line.startswith(b"#"):
                            print("Pico: " + line.decode("utf-8", errors="replace").strip(), flush=True)
                            continue
                        try:
                            payload = parse_message(line)
                        except ValueError as error:
                            print(f"Skipped serial message: {error}", file=sys.stderr, flush=True)
                            continue
                        if transaction_id is not None and payload["tx_id"] != transaction_id:
                            print("Skipped authorization for a different transaction.", file=sys.stderr, flush=True)
                            continue
                        result = forward(payload, url, token, opener)
                        if creating or args.tx_id is not None:
                            return 0 if result is not None and result[0] == 200 else 1
            except (serial.SerialException, OSError):
                if creation_attempted:
                    print("USB disconnected during the transaction. Not resending; restart when Pico is ready.",
                          file=sys.stderr, flush=True)
                    return 1
                print("Serial connection unavailable; reconnecting in 2 seconds.", file=sys.stderr, flush=True)
                time.sleep(2)
    except (KeyboardInterrupt, EOFError):
        print("Bridge stopped.", flush=True)
        return 0


if __name__ == "__main__":
    sys.exit(main())
