// Stands in for the Pico during local testing: creates a pending transaction, signs it with
// RUNEVAULT_PICO_KEY exactly like the firmware, and sends it to /api/device/authorize.
// Usage: node bridge/fake-pico.mjs <counter> [amountCents] [dest] [glyphs]
// The counter must be higher than any counter the device has used before, including the real Pico's.
import { createHmac } from 'node:crypto';

const base = process.env.RUNEVAULT_API_URL || 'http://localhost:8080';
const token = process.env.RUNEVAULT_BRIDGE_TOKEN;
const key = process.env.RUNEVAULT_PICO_KEY;
const device = process.env.RUNEVAULT_PICO_DEVICE_ID || 'slab-01';
const [counterArg, amountArg = '50000', dest = 'acct-999', glyphs = '0-1-2'] = process.argv.slice(2);

if (!token || !key || !/^\d+$/.test(counterArg ?? '')) {
 console.error('Set RUNEVAULT_BRIDGE_TOKEN and RUNEVAULT_PICO_KEY, then run:');
 console.error('  node bridge/fake-pico.mjs <counter> [amountCents] [dest] [glyphs]');
 process.exit(1);
}

async function post(path, body) {
 const response = await fetch(`${base}/api/${path}`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Bridge-Token': token },
  body: JSON.stringify(body),
  signal: AbortSignal.timeout(60000),
 });
 const text = await response.text();
 console.log(`POST /api/${path} -> ${response.status}`);
 console.log(text);
 if (!response.ok) process.exit(1);
 return JSON.parse(text);
}

const counter = Number(counterArg);
const amount = Number(amountArg);
const pending = await post('device/transactions', { device, amount, dest, glyphs });
const signingText = `v1|${device}|${counter}|${pending.tx_id}|${amount}|${dest}|${glyphs}`;
const sig = createHmac('sha256', key).update(signingText, 'utf8').digest('hex');
await post('device/authorize', { v: 1, device, counter, tx_id: pending.tx_id, amount, dest, glyphs, sig });
