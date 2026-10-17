// Stands in for the Pico during local testing: signs a transaction with RUNEVAULT_PICO_KEY exactly
// like the firmware, and sends it to /api/device/authorize.
//
//   node bridge/fake-pico.mjs <glyphs>                       approve the dashboard's pending transfer
//   node bridge/fake-pico.mjs <glyphs> --tx <tx_id>          approve a specific pending transfer
//   node bridge/fake-pico.mjs --new [amountCents] [dest] [glyphs]   create and approve a bridge transfer
//   add --counter <n> to any form to force the counter
//
// Settings come from the environment, falling back to the repo's .env. The counter is remembered in
// bridge/.fake-pico-counter; on "Counter replay" it jumps ahead and retries, like a Pico whose
// counter.txt has fallen behind the backend.
import { createHmac } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const here = (path) => fileURLToPath(new URL(path, import.meta.url));
const envFile = here('../.env');
if (existsSync(envFile)) process.loadEnvFile(envFile); // never overrides variables already set

const base = process.env.RUNEVAULT_API_URL || 'http://localhost:8080';
const token = process.env.RUNEVAULT_BRIDGE_TOKEN;
const key = process.env.RUNEVAULT_PICO_KEY;
const device = process.env.RUNEVAULT_PICO_DEVICE_ID || 'slab-01';
const counterFile = here('./.fake-pico-counter');

const args = process.argv.slice(2);
function option(name) {
 const i = args.indexOf(name);
 if (i < 0) return undefined;
 const [value] = args.splice(i, 2).slice(1);
 return value;
}
const forcedCounter = option('--counter');
const txArg = option('--tx');
const createNew = args[0] === '--new';
if (createNew) args.shift();

const GLYPHS = /^[0-2](-[0-2]){0,63}$/;
function usage(problem) {
 if (problem) console.error(problem);
 console.error('Usage:');
 console.error('  node bridge/fake-pico.mjs <glyphs>                 e.g. 0-1-2-2-1-0');
 console.error('  node bridge/fake-pico.mjs <glyphs> --tx <tx_id>');
 console.error('  node bridge/fake-pico.mjs --new [amountCents] [dest] [glyphs]');
 process.exit(1);
}
if (!token || !key) usage('Set RUNEVAULT_BRIDGE_TOKEN and RUNEVAULT_PICO_KEY (in the environment or .env).');
if (forcedCounter !== undefined && !/^\d+$/.test(forcedCounter)) usage('--counter must be a whole number.');
if (!createNew && !GLYPHS.test(args[0] ?? '')) usage('Pass the glyphs shown on the dashboard, separated by dashes.');

async function call(method, path, body) {
 const response = await fetch(`${base}/api/${path}`, {
  method,
  headers: { 'Content-Type': 'application/json', 'X-Bridge-Token': token },
  body: body === undefined ? undefined : JSON.stringify(body),
  signal: AbortSignal.timeout(60000),
 });
 const text = await response.text();
 return { status: response.status, ok: response.ok, text, json: text ? JSON.parse(text) : null };
}

function fail(what, response) {
 console.error(`${what} failed: HTTP ${response.status} ${response.text}`);
 process.exit(1);
}

let txId, amount, dest, glyphs;
if (createNew) {
 amount = Number(args[0] ?? 50000);
 dest = args[1] ?? 'acct-999';
 glyphs = args[2] ?? '0-1-2';
 const created = await call('POST', 'device/transactions', { device, amount, dest, glyphs });
 if (!created.ok) fail('Creating the transaction', created);
 txId = created.json.tx_id;
} else {
 glyphs = args[0];
 // Same lookups the real bridge uses; amount and destination come from the backend, not the caller.
 const pending = txArg
  ? await call('GET', `device/transactions/${encodeURIComponent(txArg)}?device=${encodeURIComponent(device)}`)
  : await call('GET', `device/pending?device=${encodeURIComponent(device)}`);
 if (pending.status === 204) {
  console.error(`No live pending transaction for ${device}. Create one on the dashboard first (it expires after 2 minutes).`);
  process.exit(1);
 }
 if (!pending.ok) fail('Finding the pending transaction', pending);
 ({ tx_id: txId, amount, dest } = pending.json);
}
console.log(`Transaction ${txId}: ${(amount / 100).toFixed(2)} USD to ${dest}, glyphs ${glyphs}`);

const stored = existsSync(counterFile) ? Number(readFileSync(counterFile, 'utf8').trim()) : 0;
let counter = forcedCounter !== undefined ? Number(forcedCounter) : (Number.isSafeInteger(stored) ? stored : 0) + 1;
for (let step = 1; ; step *= 2) {
 // Consume the counter before sending, exactly as the firmware does.
 writeFileSync(counterFile, String(counter));
 const signingText = `v1|${device}|${counter}|${txId}|${amount}|${dest}|${glyphs}`;
 const sig = createHmac('sha256', key).update(signingText, 'utf8').digest('hex');
 const result = await call('POST', 'device/authorize', { v: 1, device, counter, tx_id: txId, amount, dest, glyphs, sig });
 const replay = result.status === 409 && /counter replay/i.test(result.text);
 if (replay && step <= 1024) {
  console.log(`Counter ${counter} was already used; trying ${counter + step}.`);
  counter += step;
  continue;
 }
 console.log(`POST /api/device/authorize (counter ${counter}) -> ${result.status}`);
 console.log(result.text);
 if (!result.ok) process.exit(1);
 const bank = result.json?.execution;
 if (bank) console.log(`Bank outcome: ${bank.receipt?.outcome ?? bank.status ?? 'see above'}`);
 break;
}
