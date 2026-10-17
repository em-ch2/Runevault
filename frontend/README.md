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

## Live feed (Person B)

All feed wiring is in `src/app/feed/feed.service.ts`. Set `feedUrl` in `src/environments/environment.ts` to the backend WebSocket and the mock is disabled. Each message is one JSON object; anything that fails `parseFeedEvent` (`src/app/feed/feed-event.ts`) is dropped.

```json
{"type":"tablet_status","device":"slab-01","state":"connected"}
{"type":"transfer_pending","tx_id":"rea433","amount":444222,"dest":"acc_0020","recipient":"Saltstone Archive Society","requester":"Mara Quill","at":"2026-10-17T12:00:00Z"}
{"type":"transfer_sealed","tx_id":"rea433","device":"slab-01","counter":5,"sig":"<64 hex>","at":"..."}
{"type":"transfer_verified","tx_id":"rea433","at":"..."}
{"type":"transfer_executed","tx_id":"rea433","glyphs":"0-1-2-1-2-0-1-2","lore":"...","risk_level":"LOW","bank_ref":"...","at":"..."}
{"type":"transfer_rejected","tx_id":"rea433","reason":"...","at":"..."}
```

`state` is `connected`, `locked` or `disconnected`. `amount` is integer cents. Glyphs are sent only with `transfer_executed`, never for rejected attempts. Browsers cannot set headers on a WebSocket, so an authenticated feed needs a token in the query string or a cookie.
