import serial
import requests
import json
import time

PORT = "COM10"
DEVICE_ID = "slab-01"
BACKEND_URL = "http://localhost:5000"
BRIDGE_TOKEN = ""

headers = {"Content-Type": "application/json"}
if BRIDGE_TOKEN != "":
    headers["X-Bridge-Token"] = BRIDGE_TOKEN

while True:
    try:
        pico = serial.Serial(PORT, 115200, timeout=1)
    except serial.SerialException:
        print("waiting for Pico on " + PORT + " (unplugged, or Thonny still open?)")
        time.sleep(2)
        continue
    print("connected to Pico on " + PORT + ", restarting firmware...")

    try:
        pico.write(b"\x03\x03")
        time.sleep(0.2)
        pico.write(b"\x04")
        ready = False
        wait_start = time.time()
        while time.time() - wait_start < 6:
            raw = pico.readline()
            if raw == b"":
                continue
            line = raw.decode(errors="replace").strip()
            if line == "# ready":
                ready = True
                break
        if ready:
            print("firmware ready")
        else:
            print("did not see '# ready' from the Pico - is main.py saved on it?")

        if BACKEND_URL == "":
            print("no BACKEND_URL set: type transfers by hand")
        else:
            print("watching " + BACKEND_URL + " for transfers to " + DEVICE_ID)

        said_waiting = False
        while True:
            if BACKEND_URL == "":
                tx_id = input("\ntx_id: ").strip()
                amount_text = input("amount ($): ").strip().replace("$", "").replace(",", "")
                dest = input("destination account: ").strip()
                dollars_part = amount_text
                cents_part = "00"
                if "." in amount_text:
                    pieces = amount_text.split(".")
                    if len(pieces) != 2:
                        print("invalid amount, try again (like 500 or 500.25)")
                        continue
                    dollars_part = pieces[0]
                    cents_part = pieces[1]
                    if len(cents_part) == 1:
                        cents_part = cents_part + "0"
                if dollars_part == "":
                    dollars_part = "0"
                if not dollars_part.isdigit() or not cents_part.isdigit() or len(cents_part) != 2:
                    print("invalid amount, try again (like 500 or 500.25)")
                    continue
                amount = str(int(dollars_part) * 100 + int(cents_part))
            else:
                try:
                    response = requests.get(BACKEND_URL + "/api/pending", params={"device": DEVICE_ID}, headers=headers, timeout=5)
                except requests.RequestException as error:
                    print("could not reach backend: " + str(error))
                    time.sleep(3)
                    continue
                if response.status_code == 204:
                    if not said_waiting:
                        print("\nwaiting for a transfer request...")
                        said_waiting = True
                    time.sleep(2)
                    continue
                if response.status_code != 200:
                    print("backend replied " + str(response.status_code) + ": " + response.text)
                    time.sleep(3)
                    continue
                said_waiting = False
                pending = response.json()
                tx_id = str(pending.get("tx_id", ""))
                amount = str(pending.get("amount", ""))
                dest = str(pending.get("dest", ""))
                if not amount.isdigit():
                    print("backend sent a bad amount, skipping")
                    time.sleep(3)
                    continue

            if tx_id == "" or dest == "" or "|" in tx_id or "|" in dest:
                print("invalid transfer details, try again")
                continue
            if len(amount) > 10 or int(amount) == 0:
                print("amount must be between $0.01 and $99,999,999.99")
                continue
            if len(tx_id) > 64 or len(dest) > 64 or '"' in tx_id or '"' in dest:
                print("tx_id and destination must be under 64 characters, no quotes")
                continue
            print("\ntransfer " + tx_id + ": $" + str(int(amount) // 100) + "." + str(int(amount) % 100).zfill(2) + " to " + dest)

            pico.reset_input_buffer()
            pico.write(("TX|" + tx_id + "|" + amount + "|" + dest + "\n").encode())
            print("press the glyphs shown on the dashboard, then tap Enter (hold Enter 2s to clear)")

            signed_line = None
            while signed_line is None:
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
                if "error" in message:
                    print("pico refused: " + message["error"])
                    signed_line = "ERROR"
                    continue
                signed_line = line

            if signed_line == "ERROR":
                continue

            print("signed: " + signed_line)

            if BACKEND_URL == "":
                print("(no BACKEND_URL set yet, not sending)")
                continue

            for attempt in range(3):
                try:
                    response = requests.post(BACKEND_URL + "/api/authorize", data=signed_line, headers=headers, timeout=10)
                    try:
                        result = response.json().get("result", "")
                    except ValueError:
                        result = ""
                    if result == "approved":
                        print("APPROVED - money can move")
                    elif result == "wrong_code":
                        print("wrong glyphs - " + str(response.json().get("tries_left")) + " tries left")
                    elif result == "locked":
                        print("too many wrong tries - transfer locked")
                    else:
                        print("backend replied " + str(response.status_code) + ": " + response.text)
                    break
                except requests.RequestException as error:
                    print("send failed (attempt " + str(attempt + 1) + "/3): " + str(error))
                    time.sleep(2)

    except serial.SerialException:
        print("lost connection to Pico, reconnecting...")
        try:
            pico.close()
        except Exception:
            pass
        time.sleep(2)
