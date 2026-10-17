# Runevault

Three-person hackathon starter. Read docs/protocol.md before changing messages.

| Owner | Files |
| --- | --- |
| A: hardware | hardware/, bridge/, docs/protocol.md |
| B: security/platform | backend/, infra/ |
| C: AI/transactions/UI | backend integrations, frontend/ |

Start in PowerShell: install Docker Desktop, copy `.env.example` to `.env`, fill in the required backend credentials, then run:

```powershell
docker compose build frontend
docker compose up -d --build
```

Compose builds Angular with `npm ci` inside the frontend image; a local `frontend/dist` folder is not needed. To view startup logs, run `docker compose logs -f frontend backend`. Do not use `docker compose down -v` when stopping services, because it deletes the PostgreSQL volume.
Backend: http://localhost:8080/api/health
Dashboard: http://localhost:4200. For Angular development outside Docker, run `npm start` in `frontend/`. See frontend/README.md.
For a temporary public HTTPS URL used by a USB bridge on another computer, see [the bridge tunnel instructions](bridge/README.md#temporary-public-https-access-for-a-bridge-on-another-computer). Keep the Auth0 audience unchanged.
Normal demo mode uses the physical Pico to verify a transaction, then Person C's execution pipeline may hand it to Nessie sandbox banking. Nessie is a sandbox; it does not move real money. To replace the Pico for a separate hardware-simulation test, stop the normal backend and explicitly start `dev,simulator`:

```powershell
docker compose stop backend
docker compose run --service-ports -e SPRING_PROFILES_ACTIVE=dev,simulator -e NESSIE_ENABLED=false backend
```

Keep `NESSIE_ENABLED=false` for hardware simulation; combining `dev,simulator` with Nessie enabled fails startup. In another PowerShell terminal, set `RUNEVAULT_BRIDGE_TOKEN` and `RUNEVAULT_DEVICE_SECRET` to the same values used by Compose, then run `node bridge/simulator.mjs` with Node 22+. Resume the normal Auth0 backend with `docker compose up -d backend`. Hardware-simulator responses are labeled `hardware-simulated` and `local-fake`; persisted `SIMULATED` records cannot enter the physical execution claim pipeline. Simulator routes are unavailable in `auth` mode.

The legacy challenge flow uses only a local fake bank adapter. The Angular dashboard uses Auth0 login and can create and read pending backend transactions; its separate demo preview still uses scripted transfer data. Hardware-simulator-to-Nessie testing is unsupported.

Normal `dev` and `auth` application profiles require Nessie sandbox banking. Set `NESSIE_API_KEY` and `NESSIE_SOURCE_ACCOUNT_ID` (a Nessie account UUID) privately in `.env`; Compose sets `NESSIE_ENABLED=true`. Set `RUNEVAULT_ALLOWED_DESTINATIONS` to the permitted Nessie destination account UUIDs for physical demo transactions. Missing banking configuration fails startup. The fake bank runs only in `test` or explicit hardware `simulator` profiles. No live Nessie request is made by the automated tests.

After the Pico verifies a transaction, the backend claims it once and calls Nessie outside the claim transaction. A pending Nessie receipt becomes `BANK_PENDING`, a confirmed completion becomes `EXECUTED`, a definite rejection becomes `FAILED`, and an ambiguous submitted result becomes `OUTCOME_UNKNOWN`. The provider, receipt when available, status, fixed reason, and timestamps persist with a bank-outcome audit event. Pending and unknown outcomes are never marked completed or resent automatically. Operators must inspect Nessie and the audit history before manually resolving an unknown outcome; background reconciliation and retries are not implemented. Historical rows created by the older receipt-ID rule are reclassified conservatively without deleting data.

Successful signed simulations are saved to PostgreSQL in the `transactions` table, with the challenge ID as transaction ID, the USD amount in minor units, destination, status, and creation/update timestamps. Compose starts PostgreSQL before the backend and retains records in the `postgres-data` volume across restarts. `docker compose down -v` deletes that data.

Set `POSTGRES_PASSWORD` in `.env` before the first startup. For a backend running outside Compose, start the database with `docker compose up -d postgres`, and set `SPRING_DATASOURCE_PASSWORD` to the same password. The default JDBC URL is `jdbc:postgresql://localhost:5432/runevault` with username `runevault`; override with `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME` as needed. PostgreSQL initialization credentials apply only when the data volume is first created.

Hibernate updates the local demo schema automatically. Tests use an isolated H2 database in PostgreSQL compatibility mode and do not require a running database. Simulator challenges remain in memory and are available only under `dev,simulator`; persisting simulation receipts does not implement durable challenge/replay storage or production schema migrations.

Local Java builds require Java 21 and Maven 3.9+: `mvn -f backend/pom.xml verify`. Your installed Java 8 cannot build this project. Docker builds use Java 21.

Use small branches such as codex/a-firmware, codex/b-auth0, codex/c-gemini. Keep secrets out of Git. See docs/roadmap.md.

Security foundation and Auth0 setup: [docs/security-platform.md](docs/security-platform.md). Default dev now requires the bridge credential; the simulator supplies it. The auth profile validates JWTs; production remains blocked pending durable storage.

For the transaction demo, set `RUNEVAULT_MAX_AMOUNT_MINOR` (positive integer cents, at most 100000000), `RUNEVAULT_ALLOWED_DESTINATIONS` (comma-separated account IDs such as `acct-999,harbor`), and `RUNEVAULT_MAX_INCORRECT_GLYPH_ATTEMPTS` (positive integer) in `.env`. The defaults are 100000000 cents, `acct-999,harbor`, and five attempts. The amount and destination policy applies to dashboard and bridge pending-transaction creation and the older bridge challenge route. It is checked again after a valid signature, before approval. Policy rejections are audited with fixed reason codes; rejected creation does not save a transaction. Change these settings and restart the backend to apply them.

For a tighter local demo, use `RUNEVAULT_MAX_AMOUNT_MINOR=50000`, `RUNEVAULT_ALLOWED_DESTINATIONS=acct-999,harbor`, and `RUNEVAULT_MAX_INCORRECT_GLYPH_ATTEMPTS=3`. This permits a $500.00 transfer to `acct-999`, rejects $500.01, and locks the Pico after the third incorrect glyph sequence.
