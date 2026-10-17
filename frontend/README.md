# Poneglyph dashboard

Angular 21 single-page app. Node 22.12+ or 24.

```
npm install
npm start          # http://localhost:4200, plays the mock transfer
npm test -- --watch=false
npm run build      # dist/poneglyph/browser, served by the compose nginx service
```

## Auth0 login

The dashboard uses the Auth0 Angular SDK with hosted Universal Login and authorization code flow with PKCE. The public domain, client ID, and API audience are in `src/environments/environment.ts`; there are no browser-side passwords or backend secrets. The sign-in and create-account buttons redirect to Auth0, and sign-out clears the Auth0 session.

In Auth0, configure client `VXOx3TrDK15L7XPejXwXYFQEtpRqXYlH` as a **Single Page Application**. For local development and the Compose frontend, set:

- Allowed Callback URLs: `http://localhost:4200`
- Allowed Logout URLs: `http://localhost:4200/login`
- Allowed Web Origins: `http://localhost:4200`
- Allowed Origins (CORS), if used: `http://localhost:4200`

The SDK requests an access token for audience `https://api.runevault`. The API must exist in the same tenant and be authorized for this SPA client. For another deployed frontend origin, add that exact origin to the Auth0 settings above; its logout URL is `<origin>/login`. Route guards wait for SDK initialization before deciding whether to show the dashboard or sign-in screen. Browser authentication alone does not send API requests or replace backend permission checks.

## Live transactions

The dashboard opens in **Live API** mode. It calls `POST /api/transactions` with `{device, amount, dest, glyphs}`; `amount` is a whole number of cents and glyphs are a dash-separated sequence of 0, 1, and 2. The returned backend `tx_id` appears on the dashboard. Give that ID to the bridge operator for `--tx-id`; the browser never receives the bridge token or signing key. `GET /api/transactions` loads recent records and is polled every three seconds while the selected record is `PENDING` and its two-minute approval window is open. A `SIMULATED` status means Pico authorization was recorded; it is not a bank transfer or receipt.

The Auth0 user needs `create:transactions` and `read:transactions` permissions. The Angular HTTP service requests a token for `https://api.runevault` and sends it only with these Runevault transaction requests. A 401 asks the user to sign in again, a 403 indicates missing permission, and unavailable backend and validation errors are shown in the live panel.

For `npm start`, `proxy.conf.json` sends `/api` to `http://localhost:8080`. For Docker Compose, `nginx.conf` sends `/api/` to the backend container. Set `AUTH0_ISSUER_URI` in the repository-root `.env` and start the backend before testing live requests. The **Demo preview** button runs the existing scripted feed separately and never posts a transaction. No physical approval is performed by Angular.

## Live feed (Person B)

All feed wiring is in `src/app/feed/feed.service.ts`. Set `feedUrl` in `src/environments/environment.ts` to the backend WebSocket and the mock is disabled. Each message is one JSON object; anything that fails `parseFeedEvent` (`src/app/feed/feed-event.ts`) is dropped.

```json
{"type":"tablet_status","device":"slab-01","state":"connected"}
{"type":"transfer_pending","tx_id":"rea433","amount":444222,"dest":"acc_0020","recipient":"East Blue Relief Fund","requester":"Nami","at":"2026-10-17T12:00:00Z"}
{"type":"transfer_sealed","tx_id":"rea433","device":"slab-01","counter":5,"sig":"<64 hex>","at":"..."}
{"type":"transfer_verified","tx_id":"rea433","at":"..."}
{"type":"transfer_executed","tx_id":"rea433","glyphs":"0-1-2-1-2-0-1-2","lore":"...","risk_level":"LOW","bank_ref":"...","at":"..."}
{"type":"transfer_rejected","tx_id":"rea433","reason":"...","at":"..."}
```

`state` is `connected`, `locked` or `disconnected`. `amount` is integer cents. Glyphs are sent only with `transfer_executed`, never for rejected attempts. Browsers cannot set headers on a WebSocket, so an authenticated feed needs a token in the query string or a cookie.
