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

On Linux/macOS, create the environment with `python3 -m venv .venv`, use `.venv/bin/python` for the commands above, export those same environment variables, and use the actual serial port (such as `/dev/ttyACM0` or `/dev/cu.usbmodem...`). Close serial monitors that already hold the port. Environment variables are read directly; `.env` is not loaded automatically.

The Pico must send one authorization JSON object per LF-terminated line (CRLF also works), at most 4096 bytes before LF, with `v`, `device`, `counter`, `tx_id`, `amount`, `dest`, `glyphs`, and `sig`. The bridge forwards the fields to `POST /api/device/authorize` using `X-Bridge-Token`. It never needs the device HMAC key and never generates signatures.

Create a pending transaction first through `POST /api/device/transactions` and give its returned `tx_id` to the Pico. See [the protocol](../docs/protocol.md) for provisioning and payload details. This bridge only forwards authorizations; it does not create pending transactions or write commands to the Pico.

Each backend response prints one JSON line containing `http_status`, `approved`, and `response`, including rejections such as wrong glyphs, replay, and lockout. Malformed serial messages are skipped. Serial disconnects trigger reconnect attempts every two seconds. HTTP requests time out after ten seconds and are never automatically retried: a lost response can mean the backend already consumed the counter. Redirects are refused to avoid forwarding the bridge token to another endpoint. Press Ctrl+C to stop.

Tests (mock serial input and a local HTTP test server; no Pico or backend required):

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s bridge -p "test_*.py"
```
