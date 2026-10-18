# Runevault
Every treasure needs a seal.

Runevault is a physical payment-approval key disguised as an ancient Poneglyph. On the surface, it's a stone tablet straight out of *One Piece*. Underneath, it's a hardware security key that cryptographically signs one exact transfer, sends it through backend verification and a Gemini risk review, and only then lets the payment set sail through Capital One's Nessie sandbox bank.

Live site: [sailthedeal.fishing](https://sailthedeal.fishing)

Devpost link (with demo video): https://devpost.com/software/runevault

Built @ SwampHacks 2026.

## Inspiration
In most companies, sending a large payment is guarded by little more than a username and password. One shared login or compromised account can launch a costly transfer that's hard to undo, and approving a six-figure payment often takes a single click.

We wanted every high-value payment to be approved on purpose, by the right people, with something you can hold that doesn't rely on the internet to sign.

We drew inspiration from the Poneglyphs in *One Piece*: ancient stone tablets that guard secrets only the right reader can unlock. Runevault is a security key disguised as one of those relics. Every important transfer must be sealed by hand before it sets sail.

## Features
**📜 Physical sign-off:** The requester enters a one-time glyph sequence on the Runevault tablet, with lights confirming each step. The tablet signs that exact transfer (amount, destination, glyphs and a counter) with HMAC-SHA256. The tablet itself has no network connection.

**🔐 Verification:** The backend checks that the signature is genuine, matches the stored transfer, comes from the registered tablet, and has never been used before. Glyph sequences expire after two minutes, and repeated wrong guesses lock the device.

**🧭 AI risk review:** Gemini turns the glyphs into ancient lore and returns a risk score with a plain-English explanation. Gemini advises, but fixed backend rules make the final call: confidence of at least 0.8 and risk of at most 30. If Gemini fails, times out or answers badly, the transfer is blocked. High-risk transfers are flagged for CFO review instead of slipping through.

**👑 Second-person approval:** A CFO must release each verified transfer, and no one can approve their own request, even with the CFO role.

**⚓ Secure transfers:** Only after every check passes does Runevault claim the transfer exactly once and send it through the Nessie API. Nothing is ever resent automatically.

**🗺️ Live captain's log:** The dashboard updates in real time with status, risk gauges, lore, receipts, and an approvals-vs-blocks chart. A read-only **Explain decision** button asks Gemini to explain the recorded result, and it can never change a transfer.

**🛡️ Role-based access:** Every request is checked on the backend against Auth0 permissions:

| Role | What they can do |
| --- | --- |
| Employee | Create and track their own transfers |
| CFO | Release other people's verified transfers |
| Auditor | Read the company audit log and policy charts |
| Administrator | Revoke devices and recover stuck transfers |

## Architecture & Tech Stack
Our dashboard is built with Angular and TypeScript, styled with weathered stone, parchment and ocean tones in a navy and gold palette.

The physical key is a Raspberry Pi Pico 2 running MicroPython. It signs each transfer and keeps a counter that blocks replays. A USB bridge carries approvals from the tablet to our backend.

Our Java and Spring Boot backend runs every transfer through one pipeline, so no transfer can skip a check:

1. Verify the signature, device, counter, and rate limits.
2. Send only transfer metadata and server-computed risk features to Gemini, which returns schema-enforced JSON.
3. Apply our fixed policy rules, and route the transfer to a CFO for sign-off.
4. Claim the transfer once under a database lock, call Nessie, and build the receipt.
5. Push each step live over Spring WebSockets and record it in Tiger Data (TimescaleDB) for charts.

Auth0 handles login and roles. Everything runs in Docker on a Vultr server behind Caddy, at our GoDaddy Registry domain, sailthedeal.fishing.

## Technology Used
- **Hardware:** Raspberry Pi Pico 2, MicroPython, USB serial bridge (Python) and Web Serial
- **Frontend:** Angular 21, TypeScript, Auth0 SPA SDK, GSAP
- **Backend:** Java 25, Spring Boot 3.5, Spring Security, Spring WebSockets, Flyway
- **AI:** Google Gemini with structured JSON output
- **Banking:** Capital One Nessie sandbox API
- **Database:** PostgreSQL locally, Tiger Cloud (TimescaleDB hypertables and continuous aggregates) in the cloud
- **Identity:** Auth0
- **Deployment:** Docker Compose on Vultr, Caddy with automatic HTTPS, GoDaddy Registry domain

## Getting Started
### Prerequisites
- Docker Desktop
- An Auth0 tenant with the Runevault API and roles ([setup guide](docs/security-platform.md))
- A Google Gemini API key
- A Capital One Nessie API key and account IDs
- A Raspberry Pi Pico 2 flashed with [hardware/pico_main.py](hardware/pico_main.py) (or use the simulator below)
- For builds outside Docker: Java 25, Maven 3.9+, and Node.js 22+

### Installation
Clone the repository:
```powershell
git clone https://github.com/em-ch2/Runevault.git
cd Runevault
```

Set up your environment:
```powershell
Copy-Item .env.example .env
# Fill in your bridge token, Auth0 issuer and audience, PostgreSQL password,
# Pico key, Gemini key, Nessie key and source account, and allowed destinations.
```

Launch the fleet:
```powershell
docker compose up -d --build
```

- Dashboard: http://localhost:4200
- API health: http://localhost:8080/api/health

> ⚠️ Never run `docker compose down -v`; it deletes the database volume and all transfer history. Never commit `.env`.

### Seal your first transfer
1. Sign in to the dashboard and create a transfer to an allowed Nessie destination.
2. Send it to the Pico through the dashboard's Web Serial connection or the [USB bridge](bridge/README.md).
3. Enter the glyphs on the tablet and press Enter.
4. Watch it move through verification and the Gemini review to CFO sign-off and the bank receipt.

No Pico on board? Run the [hardware simulator](bridge/README.md) with `node bridge/simulator.mjs` and the `dev,simulator` profile, which uses a local fake bank only.

### Running tests
```powershell
mvn -f backend/pom.xml verify          # backend; the PostgreSQL tests need Docker
cd frontend; npm ci; npx ng test --watch=false; npm run build
```

## Security & Deployment
The cloud sandbox runs `compose.cloud.yaml` on Vultr behind Caddy with automatic HTTPS, using Tiger Cloud as the database ([deployment guide](infra/README.md)). API keys, the bridge token and device secrets live only in server-side environment variables and are never sent to the browser or committed to version control.

Runevault fails safe at every step:
- Bad signatures, replays, expired glyphs, high risk, AI outages and self-approval all stop the transfer, and each rejection writes a fixed-reason audit record.
- Rate limits are applied per user, per bridge, and per client address.
- An uncertain bank outcome is never retried automatically and is left for operator review.
- The `prod` profile deliberately refuses to start, because Runevault is a sandbox and does not move real money.

Want the deep dive? See the [security platform](docs/security-platform.md), [signing protocol](docs/protocol.md), [event contracts](docs/events.md), and [database migrations](docs/database-migrations.md).

## Challenges We Ran Into
The tablet and backend had to produce identical signatures down to the byte. Tiny formatting differences broke valid approvals until we wrote a shared signing spec.

We started with two approval flows and merged them into one, so no transfer can skip a check.

We had to keep Gemini helpful without letting it make security decisions. Deploying in under 12 hours was its own storm: DNS, HTTPS, Auth0 redirects and WebSockets all had to line up on a brand-new domain.

## Accomplishments We're Proud Of
Runevault works end to end. A button press on the tablet travels over USB, gets verified, is reviewed by Gemini, is released by a CFO, goes through Nessie, and shows up live on the dashboard.

We covered the edge cases so that every failure is a safe one. Bad signatures, replays, high risk, expired glyphs and AI outages are all stopped, and none of them can lead to an accidental payment.

Most of all, we turned payment approval into something that feels like uncovering an ancient artifact, and we built something that isn't purely software.

## What We Learned
- **Hardware security:** signatures, counters, replay protection, and how strict byte-level protocols are.
- **Responsible AI:** getting reliable JSON from Gemini, and letting AI advise rather than decide.
- **Identity & access:** building role-based access with Auth0 and enforcing it on the backend.
- **Time-series data:** using TimescaleDB hypertables and aggregates for live charts.
- **Cloud deployment:** going from localhost to a live HTTPS domain on Vultr.

## What's Next
If we keep sailing, our roadmap includes:

- **Tiered & multi-step approvals:** approval limits per role (Department Head up to $10,000, CFO up to $250,000, CEO any amount), and big transfers that need more than one approver.
- **More custom glyphs:** more than our current three, for longer sequences and richer lore from Gemini.
- **Banking integrations:** bringing the physical seal to real corporate banking platforms.
- **Hardware prototype:** a compact, lightweight token that's easy to carry on every voyage.

Made with ⚓ by the Runevault crew: Angelica, Emily, and Leonna
