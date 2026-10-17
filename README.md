# Runevault

Three-person hackathon starter. Read docs/protocol.md before changing messages.

| Owner | Files |
| --- | --- |
| A: hardware | hardware/, bridge/, docs/protocol.md |
| B: security/platform | backend/, infra/ |
| C: AI/transactions/UI | backend integrations, frontend/ |

Start: install Docker Desktop, copy .env.example to .env, then run `docker compose up --build`.
Backend: http://localhost:8080/api/health
Dashboard: build it first with `cd frontend && npm install && npm run build`, then http://localhost:4200. For development run `npm start` in frontend/ instead. See frontend/README.md.
To run the legacy simulator, stop the normal backend and explicitly start `dev,simulator`:

```powershell
docker compose stop backend
docker compose run --service-ports -e SPRING_PROFILES_ACTIVE=dev,simulator backend
```

In another PowerShell terminal, set `RUNEVAULT_BRIDGE_TOKEN` and `RUNEVAULT_DEVICE_SECRET` to the same values used by Compose, then run `node bridge/simulator.mjs` with Node 22+. Resume the normal Auth0 backend with `docker compose up -d backend`. The simulator routes are unavailable in the normal `auth` profile, and simulator records cannot enter the live execution claim pipeline.

The legacy challenge flow uses only a fake bank adapter. No money moves. The Angular dashboard uses Auth0 login and can create and read pending backend transactions; its separate demo preview still uses scripted transfer data. Gemini, Nessie, the dashboard WebSocket and production deployment remain team tasks.

Successful signed simulations are saved to PostgreSQL in the `transactions` table, with the challenge ID as transaction ID, the USD amount in minor units, destination, status, and creation/update timestamps. Compose starts PostgreSQL before the backend and retains records in the `postgres-data` volume across restarts. `docker compose down -v` deletes that data.

Set `POSTGRES_PASSWORD` in `.env` before the first startup. For a backend running outside Compose, start the database with `docker compose up -d postgres`, and set `SPRING_DATASOURCE_PASSWORD` to the same password. The default JDBC URL is `jdbc:postgresql://localhost:5432/runevault` with username `runevault`; override with `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME` as needed. PostgreSQL initialization credentials apply only when the data volume is first created.

Hibernate updates the local demo schema automatically. Tests use an isolated H2 database in PostgreSQL compatibility mode and do not require a running database. Simulator challenges remain in memory and are available only under `dev,simulator`; persisting simulation receipts does not implement durable challenge/replay storage or production schema migrations.

Local Java builds require Java 21 and Maven 3.9+: `mvn -f backend/pom.xml verify`. Your installed Java 8 cannot build this project. Docker builds use Java 21.

Use small branches such as codex/a-firmware, codex/b-auth0, codex/c-gemini. Keep secrets out of Git. See docs/roadmap.md.

Security foundation and Auth0 setup: [docs/security-platform.md](docs/security-platform.md). Default dev now requires the bridge credential; the simulator supplies it. The auth profile validates JWTs; production remains blocked pending durable storage.

For the transaction demo, set `RUNEVAULT_MAX_AMOUNT_MINOR` (positive integer cents, at most 100000000), `RUNEVAULT_ALLOWED_DESTINATIONS` (comma-separated account IDs such as `acct-999,harbor`), and `RUNEVAULT_MAX_INCORRECT_GLYPH_ATTEMPTS` (positive integer) in `.env`. The defaults are 100000000 cents, `acct-999,harbor`, and five attempts. The amount and destination policy applies to dashboard and bridge pending-transaction creation and the older bridge challenge route. It is checked again after a valid signature, before approval. Policy rejections are audited with fixed reason codes; rejected creation does not save a transaction. Change these settings and restart the backend to apply them.

For a tighter local demo, use `RUNEVAULT_MAX_AMOUNT_MINOR=50000`, `RUNEVAULT_ALLOWED_DESTINATIONS=acct-999,harbor`, and `RUNEVAULT_MAX_INCORRECT_GLYPH_ATTEMPTS=3`. This permits a $500.00 transfer to `acct-999`, rejects $500.01, and locks the Pico after the third incorrect glyph sequence.
