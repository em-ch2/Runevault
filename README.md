# Runevault

Three-person hackathon starter. Read docs/protocol.md before changing messages.

| Owner | Files |
| --- | --- |
| A: hardware | hardware/, bridge/, docs/protocol.md |
| B: security/platform | backend/, infra/ |
| C: AI/transactions/UI | backend integrations, frontend/ |

Start: install Docker Desktop, copy .env.example to .env, then run `docker compose up --build`.
Backend: http://localhost:8080/api/health
Dashboard placeholder: http://localhost:4200
Run `node bridge/simulator.mjs` with Node 22+ to submit a signed simulated approval.

Only local simulation is implemented. No money moves. Auth0, Gemini, Nessie, Angular and production deployment remain team tasks. Do not publicly expose the unauthenticated starter.

Successful signed simulations are saved to PostgreSQL in the `transactions` table, with the challenge ID as transaction ID, the USD amount in minor units, destination, status, and creation/update timestamps. Compose starts PostgreSQL before the backend and retains records in the `postgres-data` volume across restarts. `docker compose down -v` deletes that data.

Set `POSTGRES_PASSWORD` in `.env` before the first startup. For a backend running outside Compose, start the database with `docker compose up -d postgres`, and set `SPRING_DATASOURCE_PASSWORD` to the same password. The default JDBC URL is `jdbc:postgresql://localhost:5432/runevault` with username `runevault`; override with `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME` as needed. PostgreSQL initialization credentials apply only when the data volume is first created.

Hibernate updates the local demo schema automatically. Tests use an isolated H2 database in PostgreSQL compatibility mode and do not require a running database. Challenges remain in memory, and production mode stays disabled; persisting simulation receipts does not implement durable challenge/replay storage or production schema migrations.

Local Java builds require Java 21 and Maven 3.9+: `mvn -f backend/pom.xml verify`. Your installed Java 8 cannot build this project. Docker builds use Java 21.

Use small branches such as codex/a-firmware, codex/b-auth0, codex/c-gemini. Keep secrets out of Git. See docs/roadmap.md.

Security foundation and Auth0 setup: [docs/security-platform.md](docs/security-platform.md). Default dev now requires the bridge credential; the simulator supplies it. The auth profile validates JWTs; production remains blocked pending durable storage.
