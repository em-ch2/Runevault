# Bridge (Person A)
Node 22+: `node bridge/simulator.mjs` from the repo root. Uses a public development key. Override with RUNEVAULT_DEVICE_SECRET in the environment; .env is not automatically loaded.

## Pico 2 USB bridge

Install Python 3.10+ and run from the repository root:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r bridge/requirements.txt
.\.venv\Scripts\python.exe -m serial.tools.list_ports
$env:RUNEVAULT_SERIAL_PORT = "COM3" # Use the Pico's actual port
$env:RUNEVAULT_BRIDGE_TOKEN = "<same token configured on the backend>"
$env:RUNEVAULT_API_URL = "http://localhost:8080" # Optional default
$env:RUNEVAULT_SERIAL_BAUD = "115200" # Optional default
.\.venv\Scripts\python.exe bridge/usb_bridge.py
```

When a project-root `.env` contains `RUNEVAULT_BRIDGE_TOKEN`, the bridge reads that token automatically and uses it ahead of a stale terminal value. You can omit the token assignment above in this local Compose setup. The token is never printed. If no `.env` file is present, set the environment variable as shown.

On Linux/macOS, create the environment with `python3 -m venv .venv`, use `.venv/bin/python` for the commands above, export those same environment variables, and use the actual serial port (such as `/dev/ttyACM0` or `/dev/cu.usbmodem...`). Close serial monitors that already hold the port. Environment variables are read directly; `.env` is not loaded automatically.

The Pico must send one authorization JSON object per LF-terminated line (CRLF also works), at most 4096 bytes before LF, with `v`, `device`, `counter`, `tx_id`, `amount`, `dest`, `glyphs`, and `sig`. The bridge forwards the fields to `POST /api/device/authorize` using `X-Bridge-Token`. It never needs the device HMAC key and never generates signatures.

### Create and send a transaction to the supplied MicroPython firmware

Stop any running bridge with Ctrl+C, then run (with the environment variables above set):

```powershell
.\.venv\Scripts\python.exe bridge/usb_bridge.py --amount 50000 --dest acct-999 --glyphs 0-2-1-2-1
```

When prompted, ensure the firmware is running and waiting for a transaction, then press Enter on the computer. The bridge creates a pending transaction and writes `TX|<returned tx_id>|50000|acct-999\n` over USB. The two-minute expiry starts at creation. Expected glyphs stay on the backend; they are not sent to the Pico.

The bridge waits for the Pico firmware's `# enter glyphs for tx <id>` acknowledgment before asking you to press buttons. If no acknowledgment arrives within eight seconds, it exits with a diagnostic. Check that the firmware is saved as `main.py` (or otherwise running), that the Pico is on the chosen serial port, and that it is at its TX-reading loop. Restart the bridge for a fresh transaction after correcting the issue.

On the Pico, press and release glyph buttons **0, 2, 1, 2, 1**, then briefly press its Enter button. In the supplied firmware, glyph 0 is GP8, glyph 1 is GP3, glyph 2 is GP4, and Enter is GP2. Holding Enter longer than two seconds clears the entered sequence. The Pico increments its persisted counter, signs, and emits JSON. Firmware comments starting with `#` appear as progress messages.

The bridge submits the matching authorization, prints the response, and exits after one attempt. Run the command again for a new transaction. If USB disconnects after creation, the bridge exits without resending; ensure the Pico has returned to its TX-reading loop before trying again (reset it if it is still collecting glyphs). Keep `counter.txt` intact when resetting.

Without transaction arguments, the bridge retains its original forwarding-only behavior; a separate client must create and load pending transactions. See [the protocol](../docs/protocol.md) for provisioning and payload details.

Each backend response prints one JSON line containing `http_status`, `approved`, and `response`, including rejections such as wrong glyphs, replay, and lockout. Malformed serial messages are skipped. Serial disconnects trigger reconnect attempts every two seconds. HTTP requests time out after ten seconds and are never automatically retried: a lost response can mean the backend already consumed the counter. Redirects are refused to avoid forwarding the bridge token to another endpoint. Press Ctrl+C to stop.

Tests (mock serial input and a local HTTP test server; no Pico or backend required):

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s bridge -p "test_*.py"
```
