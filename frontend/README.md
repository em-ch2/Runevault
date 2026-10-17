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
- Allowed Logout URLs: `http://localhost:4200`
- Allowed Web Origins: `http://localhost:4200`
- Allowed Origins (CORS), if used: `http://localhost:4200`

The SDK requests an access token for audience `https://api.runevault`. The API must exist in the same tenant and be authorized for this SPA client. For another deployed frontend origin, add that exact origin to the Auth0 settings above; its logout URL is `<origin>`. Route guards wait for SDK initialization before deciding whether to show the dashboard or sign-in screen. Browser authentication alone does not send API requests or replace backend permission checks.

## Live transactions

The dashboard opens in **Live API** mode. It calls `POST /api/transactions` with `{device, amount, dest}`; `amount` is a whole number of cents. Gemini generates a six-glyph sequence and the response returns it for the user to enter on the Pico. The returned backend `tx_id` appears on the dashboard; a running bridge automatically polls for pending transfers. The browser never receives the bridge token or signing key. `GET /api/transactions` loads recent records and is polled every three seconds while the selected record is pending, physically verified, executing, or accepted by Nessie but still pending. `VERIFIED` and `verifiedAt` mean physical approval, `EXECUTING` means the one-time claim, `BANK_PENDING` means Nessie accepted a transfer that is not confirmed complete, and `OUTCOME_UNKNOWN` requires manual review. Only `EXECUTED` means the provider confirmed completion. Historical `SIMULATED` rows remain legacy simulations without physical verification.

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

### Login and logout troubleshooting

The Runevault sign-in and signup screens intentionally open Auth0 Universal Login. Passwords, signup, password reset and MFA are handled by Auth0, not by Angular. Use an application test user; signing into the Auth0 management dashboard does not create a Runevault user. New users need their API permissions assigned before Live API requests will succeed; signup never grants administrator privileges.

Logout returns to the current site root. Under Applications > Runevault Dashboard > Settings, add the exact frontend origin to Allowed Logout URLs and save. For localhost use http://localhost:4200. For an HTTPS frontend tunnel or deployment add its exact origin too; a backend-only tunnel does not change frontend Auth0 URLs. Keep Allowed Callback URLs and Allowed Web Origins aligned with the frontend origin. Different hosts (localhost vs 127.0.0.1) and ports require their own entries.

Validation: sign in as an application user, confirm your name appears on the dashboard, refresh, create/list a transaction with the required permissions, sign out, and confirm the protected dashboard returns to the sign-in screen. Retry signup using a separate test account. If Auth0 rejects a URL, compare its reported returnTo or redirect_uri to the saved application allowlist. No frontend build can change Auth0 tenant settings automatically.

Explicit sign-in and signup request prompt=login and max_age=0 so an existing Auth0 session is not silently reused. Upstream Google sessions are independent: to guarantee a Google account chooser, configure the Google social connection's upstream prompt parameter to select_account in Auth0. This requires tenant configuration; the Angular request alone does not guarantee a new Google password challenge. Runevault logout does not sign the browser out of Gmail.
