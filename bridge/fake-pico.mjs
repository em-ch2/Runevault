// Stands in for the Pico during local testing: signs a transaction with RUNEVAULT_PICO_KEY exactly
// like the firmware, and sends it to /api/device/authorize.
// New transaction:      node bridge/fake-pico.mjs <counter> [amountCents] [dest] [glyphs]
// Dashboard-created one: node bridge/fake-pico.mjs <counter> --tx <tx_id> <glyphs>
// The counter must be higher than any counter the device has used before, including the real Pico's.
import { createHmac } from 'node:crypto';

const base = process.env.RUNEVAULT_API_URL || 'http://localhost:8080';
const token = process.env.RUNEVAULT_BRIDGE_TOKEN;
const key = process.env.RUNEVAULT_PICO_KEY;
const device = process.env.RUNEVAULT_PICO_DEVICE_ID || 'slab-01';
const args = process.argv.slice(2);
const counterArg = args[0];
const existing = args[1] === '--tx';

if (!token || !key || !/^\d+$/.test(counterArg ?? '') || (existing && (!args[2] || !args[3]))) {
 console.error('Set RUNEVAULT_BRIDGE_TOKEN and RUNEVAULT_PICO_KEY, then run one of:');
 console.error('  node bridge/fake-pico.mjs <counter> [amountCents] [dest] [glyphs]');
 console.error('  node bridge/fake-pico.mjs <counter> --tx <tx_id> <glyphs>');
 process.exit(1);
}

async function call(method, path, body) {
 const response = await fetch(`${base}/api/${path}`, {
  method,
  headers: { 'Content-Type': 'application/json', 'X-Bridge-Token': token },
  body: body === undefined ? undefined : JSON.stringify(body),
  signal: AbortSignal.timeout(60000),
 });
 const text = await response.text();
 console.log(`${method} /api/${path} -> ${response.status}`);
 console.log(text);
 if (!response.ok) process.exit(1);
 return JSON.parse(text);
}

const counter = Number(counterArg);
let txId, amount, dest, glyphs;
if (existing) {
 // Same lookup the real bridge uses; amount and destination come from the backend, not the caller.
 [, , txId, glyphs] = args;
 const pending = await call('GET', `device/transactions/${encodeURIComponent(txId)}?device=${encodeURIComponent(device)}`);
 ({ amount, dest } = pending);
} else {
 amount = Number(args[1] ?? 50000);
 dest = args[2] ?? 'acct-999';
 glyphs = args[3] ?? '0-1-2';
 txId = (await call('POST', 'device/transactions', { device, amount, dest, glyphs })).tx_id;
}
const signingText = `v1|${device}|${counter}|${txId}|${amount}|${dest}|${glyphs}`;
const sig = createHmac('sha256', key).update(signingText, 'utf8').digest('hex');
await call('POST', 'device/authorize', { v: 1, device, counter, tx_id: txId, amount, dest, glyphs, sig });
