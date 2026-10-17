import { createHmac } from 'node:crypto';
const base = process.env.RUNEVAULT_API_URL || 'http://localhost:8080';
const secret = process.env.RUNEVAULT_DEVICE_SECRET || 'local-demo-only-change-before-deploy';
async function post(path, body) {
 const response = await fetch(`${base}/api/${path}`, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body), signal: AbortSignal.timeout(10000) });
 if (!response.ok) throw new Error(`${response.status}: ${await response.text()}`);
 return response.json();
}
try {
 const c = await post('challenges', {deviceId:'pico-demo-1',amountMinor:2500,destination:'demo-harbor',glyphs:'ABCD'});
 const signature = createHmac('sha256',secret).update(c.signingText,'utf8').digest('hex');
 console.log(JSON.stringify(await post('approvals',{challengeId:c.challengeId,signature}),null,2));
} catch(error) { console.error(error.message); process.exitCode = 1; }
