"""Poll the Runevault backend for dashboard transfers, hand them to the Pico, forward its signatures.

The bridge never holds the device key and never signs anything itself.

Settings (environment variables):
  RUNEVAULT_SERIAL_PORT   Pico serial port, default COM10
  RUNEVAULT_DEVICE_ID     device the dashboard assigns transfers to, default slab-01
  RUNEVAULT_API_URL       backend base URL, default http://localhost:8080
  RUNEVAULT_BRIDGE_TOKEN  read from the project-root .env first, then the environment
"""

import json
import os
import time
from pathlib import Path

import requests
import serial

PORT = os.environ.get("RUNEVAULT_SERIAL_PORT", "COM10")
DEVICE_ID = os.environ.get("RUNEVAULT_DEVICE_ID", "slab-01")
BACKEND_URL = os.environ.get("RUNEVAULT_API_URL", "http://localhost:8080").rstrip("/")
LOCAL_ENV = Path(__file__).resolve().parent.parent / ".env"


def bridge_token():
    # Same lookup as bridge/usb_bridge.py: the local Compose .env wins over a stale terminal value.
    try:
        with LOCAL_ENV.open(encoding="utf-8-sig") as env_file:
            for line in env_file:
                name, separator, value = line.strip().partition("=")
                if separator and name == "RUNEVAULT_BRIDGE_TOKEN":
                    value = value.strip().strip('"').strip("'")
                    if value != "":
                        return value
    except FileNotFoundError:
        pass
    return os.environ.get("RUNEVAULT_BRIDGE_TOKEN", "")


def restart_firmware(pico):
    """Soft-reset the Pico so it drops any half-entered transfer and waits for a new TX line."""
    pico.write(b"\x03\x03")
    time.sleep(0.2)
    pico.write(b"\x04")
    wait_start = time.time()
    while time.time() - wait_start < 6:
        raw = pico.readline()
        if raw == b"":
            continue
        if raw.decode(errors="replace").strip() == "# ready":
            return True
    return False


def dollars(cents):
    return "$" + str(cents // 100) + "." + str(cents % 100).zfill(2)


def report(response):
    try:
        body = response.json()
    except ValueError:
        body = {}
    status = response.status_code
    error = body.get("error", response.text)
    if status == 200:
        execution = body.get("execution") or {}
        print("APPROVED - signature and glyphs verified")
        if execution.get("status") == "BLOCKED":
            print("Gemini policy blocked the transfer: " + str(execution.get("reason")))
        elif execution != {}:
            print("execution: " + json.dumps(execution))
        print("the dashboard shows the bank outcome")
    elif status == 403 and error == "Incorrect glyphs":
        print("wrong glyphs - the transfer stays pending, it will be sent to the Pico again")
    elif status == 423:
        print(error + " - an operator must unlock the device before more transfers")
    elif status == 409:
        print("rejected (" + error + ") - create a fresh transfer on the dashboard")
    else:
        print("backend replied " + str(status) + ": " + response.text)


token = bridge_token()
if token == "":
    raise SystemExit("Put RUNEVAULT_BRIDGE_TOKEN in the project .env or the environment.")
headers = {"Content-Type": "application/json", "X-Bridge-Token": token}

while True:
    try:
        pico = serial.Serial(PORT, 115200, timeout=1)
    except serial.SerialException:
        print("waiting for Pico on " + PORT + " (unplugged, or Thonny still open?)")
        time.sleep(2)
        continue
    print("connected to Pico on " + PORT + ", restarting firmware...")

    try:
        if restart_firmware(pico):
            print("firmware ready")
        else:
            print("did not see '# ready' from the Pico - is main.py saved on it?")
        print("watching " + BACKEND_URL + " for transfers to " + DEVICE_ID)

        said_waiting = False
        while True:
            try:
                response = requests.get(BACKEND_URL + "/api/device/pending", params={"device": DEVICE_ID},
                                        headers=headers, timeout=5, allow_redirects=False)
            except requests.RequestException as error:
                print("could not reach backend: " + str(error))
                time.sleep(3)
                continue
            if response.status_code == 204:
                if not said_waiting:
                    print("\nwaiting for a transfer request from the dashboard...")
                    said_waiting = True
                time.sleep(2)
                continue
            if response.status_code == 401:
                print("backend rejected the bridge token - check RUNEVAULT_BRIDGE_TOKEN")
                time.sleep(10)
                continue
            if response.status_code != 200:
                print("backend replied " + str(response.status_code) + ": " + response.text)
                time.sleep(10 if response.status_code == 423 else 3)
                continue
            said_waiting = False
            pending = response.json()
            tx_id = str(pending.get("tx_id", ""))
            amount = pending.get("amount")
            dest = str(pending.get("dest", ""))
            expires_at = pending.get("expires_at")
            if (tx_id == "" or dest == "" or type(amount) is not int or type(expires_at) is not int
                    or "|" in tx_id + dest or '"' in tx_id + dest):
                print("backend sent bad transfer details, skipping")
                time.sleep(3)
                continue

            seconds_left = int(expires_at - time.time())
            print("\ntransfer " + tx_id + ": " + dollars(amount) + " to " + dest
                  + " (" + str(seconds_left) + "s left)")

            pico.reset_input_buffer()
            pico.write(("TX|" + tx_id + "|" + str(amount) + "|" + dest + "\n").encode())
            print("press the glyphs shown on the dashboard, then tap Enter (hold Enter 2s to clear)")

            signed_line = None
            while signed_line is None:
                if time.time() > expires_at:
                    print("transfer expired before it was sealed, resetting the Pico")
                    restart_firmware(pico)
                    break
                raw = pico.readline()
                if raw == b"":
                    continue
                line = raw.decode(errors="replace").strip()
                if line.startswith("#"):
                    print("[pico] " + line[1:].strip())
                    continue
                if not line.startswith("{"):
                    print("[pico raw] " + line)
                    continue
                try:
                    message = json.loads(line)
                except ValueError:
                    print("could not parse line from Pico: " + line)
                    continue
                if message.get("tx_id") != tx_id:
                    print("signed message is for a different tx, ignoring")
                    continue
                signed_line = line

            if signed_line is None:
                continue
            print("signed: " + signed_line)

            # No automatic retry: the backend may already have consumed this counter.
            try:
                response = requests.post(BACKEND_URL + "/api/device/authorize", data=signed_line,
                                         headers=headers, timeout=30, allow_redirects=False)
            except requests.RequestException as error:
                print("send failed, outcome unknown - check the dashboard: " + str(error))
                continue
            report(response)

    except serial.SerialException:
        print("lost connection to Pico, reconnecting...")
        try:
            pico.close()
        except Exception:
            pass
        time.sleep(2)
