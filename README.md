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

Only local simulation is implemented. No money moves. Auth0, Gemini, Nessie, Angular, persistence and production deployment remain team tasks. Do not publicly expose the unauthenticated starter.

Local Java builds require Java 21 and Maven 3.9+: `mvn -f backend/pom.xml verify`. Your installed Java 8 cannot build this project. Docker builds use Java 21.

Use small branches such as codex/a-firmware, codex/b-auth0, codex/c-gemini. Keep secrets out of Git. See docs/roadmap.md.
