# Poneglyph dashboard

Angular 21 single-page app. Node 22.12+ or 24.

```
npm install
npm start          # http://localhost:4200, plays the mock transfer
npm test -- --watch=false
npm run build      # dist/poneglyph/browser, served by the compose nginx service
```

Sign in with the demo account shown on the login page, or create one. Accounts are mock, stored only in this browser (`src/app/auth/auth.service.ts`); replace with Auth0 PKCE using the same `user` / `signUp` / `signIn` / `signOut` surface.

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
