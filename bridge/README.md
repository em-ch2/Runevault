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

### Authorize a transaction already created by the dashboard

From the repository root in PowerShell, start the configured backend and create the pending transaction with a dashboard access token carrying `create:transactions`. Set `$env:AUTH0_ACCESS_TOKEN` in your terminal first; do not put that JWT or the bridge token in source files.

```powershell
docker compose up -d --build postgres backend
$body = @{ device = "slab-01"; amount = 50000; dest = "<allowed-Nessie-account-UUID>" } | ConvertTo-Json
$pending = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/transactions" -Headers @{ Authorization = "Bearer $env:AUTH0_ACCESS_TOKEN" } -ContentType "application/json" -Body $body
$pending.tx_id
```

Then use the returned ID:

```powershell
$env:RUNEVAULT_SERIAL_PORT = "COM3" # Replace with the Pico port from list_ports
$env:RUNEVAULT_API_URL = "http://localhost:8080"
.\.venv\Scripts\python.exe bridge/usb_bridge.py --tx-id $pending.tx_id --device slab-01
```

The bridge reads `RUNEVAULT_BRIDGE_TOKEN` from project-root `.env` (or the environment if no local token exists). It calls `GET /api/device/transactions/<tx_id>?device=slab-01` with `X-Bridge-Token`, verifies the returned pending assignment and expiry, then sends the stored `TX|<id>|<amount>|<destination>\n` to the Pico. `--amount`, `--dest`, and `--glyphs` cannot be combined with `--tx-id`. The lookup never returns expected glyphs; obtain the assigned sequence from the trusted transaction creator. The Pico signs its response, and the bridge forwards it to `POST /api/device/authorize`.

### Temporary public HTTPS access for a bridge on another computer

On the backend computer, keep the normal `auth` profile and the existing `.env` values. From the repository root in PowerShell, run:

```powershell
docker compose up -d postgres backend
Invoke-RestMethod http://127.0.0.1:8080/api/health
docker compose -f compose.yaml -f compose.tunnel.yaml up -d --no-deps tunnel
docker compose -f compose.yaml -f compose.tunnel.yaml logs --tail=80 tunnel
```

Copy the generated `https://...trycloudflare.com` base URL from the tunnel logs; do not guess it. The optional tunnel reaches only `http://backend:8080` on the Compose network. It does not publish PostgreSQL. Verify the URL from PowerShell:

```powershell
$env:RUNEVAULT_API_URL = "https://<generated-name>.trycloudflare.com"
Invoke-RestMethod "$env:RUNEVAULT_API_URL/api/health"
curl.exe -s -o NUL -w '%{http_code}' "$env:RUNEVAULT_API_URL/api/device/transactions/missing?device=slab-01"
```

The last command must return `401` without `X-Bridge-Token`. Keep Auth0's API audience as `https://api.runevault`; the tunnel URL is only a temporary transport address.

On the hardware teammate's computer, use the same checked-out bridge code and install its requirements. Set the URL from the backend computer, select the Pico's actual port, and enter the **matching backend bridge token privately**:

```powershell
.\.venv\Scripts\python.exe -m serial.tools.list_ports
$env:RUNEVAULT_API_URL = "https://<generated-name>.trycloudflare.com"
$env:RUNEVAULT_SERIAL_PORT = "COM3" # Replace with the Pico's actual port
$secret = Read-Host "Bridge token" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try { $env:RUNEVAULT_BRIDGE_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr); Remove-Variable secret, ptr }
.\.venv\Scripts\python.exe bridge/usb_bridge.py --tx-id "<existing-pending-tx-id>" --device slab-01
```

Run these commands from the repository root. If that computer has a project-root `.env` containing `RUNEVAULT_BRIDGE_TOKEN`, the bridge uses that file's value **instead of** the terminal variable; update the file privately to match or remove its stale token entry. Do not copy the backend computer's full `.env` to the hardware computer. The dashboard transaction must still be pending and assigned to `slab-01`; start the bridge promptly because it expires after 120 seconds.

The backend computer and tunnel must remain running during the test. Quick Tunnel URLs can change after a tunnel restart; send the new URL to the hardware teammate and update `RUNEVAULT_API_URL`. This is temporary testing access, not AWS deployment.

Hardware checklist:

1. Start the backend with the existing PostgreSQL data and configured Pico key. Do not reset the database or `counter.txt`.
2. Close Thonny and other serial monitors; confirm the Pico appears with `python -m serial.tools.list_ports` and its firmware prints `# ready` when started.
3. Create a pending transaction in the dashboard and immediately run the `--tx-id` command. The pending assignment expires 120 seconds after creation.
4. Confirm the terminal prints `Pico accepted TX`; enter the transaction's assigned glyphs and briefly press the Pico's GP2 Enter button.
5. Confirm the terminal prints HTTP 200 with `approved: true`. For a 401/403/409/423 response, retain the output and inspect admin audit events. A consumed counter cannot be replayed; create a fresh transaction for another attempt.

Without transaction arguments, the bridge retains its original forwarding-only behavior; a separate client must create and load pending transactions. See [the protocol](../docs/protocol.md) for provisioning and payload details.

Each backend response prints one JSON line containing `http_status`, `approved`, and `response`, including rejections such as wrong glyphs, replay, and lockout. Malformed serial messages are skipped. Serial disconnects trigger reconnect attempts every two seconds. HTTP requests time out after ten seconds and are never automatically retried: a lost response can mean the backend already consumed the counter. Redirects are refused to avoid forwarding the bridge token to another endpoint. Press Ctrl+C to stop.

Tests (mock serial input and a local HTTP test server; no Pico or backend required):

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s bridge -p "test_*.py"
```
