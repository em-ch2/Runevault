# Hardware (Person A)

`pico_main.py` is the MicroPython firmware (save it on the Pico as `main.py`). `bridge.py` runs on the computer the Pico is plugged into and connects it to the Runevault backend and dashboard. Signing follows [the protocol](../docs/protocol.md).

## Flow

1. On the dashboard (Live API), create a transaction for device `slab-01`. Use **Random glyphs** or type a sequence; the dashboard shows the glyphs to press while the transaction is pending.
2. `bridge.py` polls `GET /api/device/pending?device=slab-01`, gets the oldest live pending transaction (never its glyphs), and sends `TX|<tx_id>|<amount>|<dest>` to the Pico.
3. Press the glyphs on the Pico, then tap Enter. The Pico signs with its key and prints the JSON authorization.
4. The bridge forwards it to `POST /api/device/authorize`. On approval the backend runs the Gemini check and the Nessie transfer, and the dashboard shows the outcome.

Wrong glyphs leave the transaction pending, so the bridge sends it to the Pico again. If a transaction expires (120 s) before it is sealed, the bridge soft-resets the Pico. A locked or revoked device gets no more work until an operator resets it.

## Setup

- Project-root `.env`: set `RUNEVAULT_PICO_KEY` to the same key as `KEY` in `pico_main.py`, and set `RUNEVAULT_BRIDGE_TOKEN`. The bridge reads the token from `.env` automatically.
- Start the backend and frontend (`docker compose up -d --build`).
- Close Thonny, then from the repo root:

```powershell
pip install -r hardware/requirements.txt
$env:RUNEVAULT_SERIAL_PORT = "COM10"            # python -m serial.tools.list_ports
$env:RUNEVAULT_API_URL = "http://localhost:8080" # optional default
python hardware/bridge.py
```

Pins in `pico_main.py`: glyph 0 (Will) = GP8, glyph 1 (Crew) = GP4, glyph 2 (Sea) = GP3, Enter = GP2, waiting LED = GP10, sealed LED = GP11. Keep `counter.txt` on the Pico; the backend rejects counters it has already seen.

`docs/server.py` was the standalone Flask prototype of this flow; the Spring backend and Angular dashboard replace it.
