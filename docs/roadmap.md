# Team queue

A: firmware buttons/debounce, canonical HMAC, Python serial bridge/reconnects, wiring documentation, enclosure, demo rehearsal. Use Node simulator before hardware is ready. Never commit real device keys. Verify actual board capabilities before claiming protected key storage or secure firmware updates.

B: extract security services; Auth0 JWT issuer/audience and roles; separate bridge authentication; per-device keys; persisted challenge/transaction state and audit; rate limits; configurable rules; Docker/AWS secrets and logs. Own infra/.

C: extract AI and bank interfaces with mock adapters; Gemini structured output and known glyph dictionary; Nessie sandbox and ambiguity-safe execution; Angular dashboard then live updates and stone animation; CI/deployment with B. Never blindly retry a transfer after a timeout.

Milestones:
1. Local simulator -> verified mock receipt.
2. Physical buttons -> verified mock receipt.
3. Gemini + Nessie sandbox + minimal dashboard.
4. Auth0 + persistent execution state + deployed demo.
5. Polish, backup video, rehearsal. Freeze features four hours before judging.

Defer microservices, multi-tenancy, ledger, queues, mobile app, anomaly learning, GitOps, autoscaling and chaos tests. Do not scale the in-memory starter. A should finish hardware before taking backend audit work.
