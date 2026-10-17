# Security platform: first implementation

## Profiles

## Device revocation

An Auth0 access token with `admin:platform` may call `POST /api/admin/devices/{deviceId}/revoke` with no request body. The backend takes the administrator identity from the verified token's `sub` claim. The response contains `deviceId`, `revoked: true`, and `revokedAt`; repeating the call returns the original timestamp and creates no duplicate revocation event. An unregistered device returns 404. Revocation retains device counters, transactions, and audit history. It blocks new pending transactions, physical authorizations, and future execution claims, including claims on earlier `VERIFIED` transactions. It cannot undo an `EXECUTING` transfer already in progress.

In PowerShell, with an administrator **access token** for audience `https://api.runevault` already obtained:

```powershell
$adminToken = '<ADMIN_ACCESS_TOKEN>'
$headers = @{ Authorization = "Bearer $adminToken" }
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/admin/devices/slab-01/revoke' -Headers $headers
Invoke-RestMethod -Method Get -Uri 'http://localhost:8080/api/admin/audit-events?page=0&size=20' -Headers $headers
```

Use a non-admin token to confirm the POST returns 403, or omit the token to confirm 401. Revocation events record the JWT subject and fixed `REVOKED_BY_ADMIN` reason; denied pending creation, authorization, and execution claims use fixed reasons without credentials or signatures. There is no reactivation endpoint in this milestone.
- Default `dev`: health and Pico bridge endpoints available; legacy challenge/approval routes unavailable.
- Explicit `dev,simulator` with `NESSIE_ENABLED=false`: replaces Pico approvals with legacy challenge/approval routes and uses only the local fake bank. Responses say `hardware-simulated` and `local-fake`; persisted `SIMULATED` records cannot be physically claimed. Combining simulator with `auth` or enabling Nessie in simulator mode fails startup. Hardware-simulator-to-Nessie is unsupported.
- `auth`: same bridge gate plus Auth0 JWT validation for dashboard API routes. Admin status requires `admin:platform` permission. Set SPRING_PROFILES_ACTIVE=auth, AUTH0_ISSUER_URI (HTTPS issuer with trailing slash), AUTH0_AUDIENCE, RUNEVAULT_BRIDGE_TOKEN (random 32+ bytes), and RUNEVAULT_DEVICE_SECRET.
- `prod`: deliberately refuses startup. Persistent replay and execution storage are not ready. Do not bypass this guard to deploy real transfers.

Auth0 tenant setup (Dashboard > Applications > APIs): create an API named `Runevault API`, identifier `https://api.runevault`, signing algorithm `RS256`. In its Permissions tab add `create:transactions`, `read:transactions`, and `admin:platform`. In Settings enable **RBAC** and **Add Permissions in the Access Token**. Under User Management > Roles create `Employee` with create/read transactions and `Administrator` with all three permissions. Assign the test user `Administrator` in that user's Roles tab. These steps modify the Auth0 tenant and cannot be completed by repository configuration alone.

Set `AUTH0_ISSUER_URI=https://<tenant>.auth0.com/` (the exact Auth0 issuer, including trailing slash), `AUTH0_AUDIENCE=https://api.runevault`, and a random bridge token of at least 32 bytes in local `.env`. Compose runs the backend with the `auth` profile and passes these variables. Keep the bridge token on the backend and USB bridge only. Frontend owner implements Auth0 authorization-code login with PKCE and requests an access token for audience `https://api.runevault`; send it as `Authorization: Bearer <access-token>`, not an ID token. Enable MFA in the Auth0 tenant; this repository does not configure tenant policy or implement browser login.

Dashboard `POST /api/transactions` requires `create:transactions` and accepts `{ "device": "slab-01", "amount": 50000, "dest": "acct-999", "glyphs": "0-2-1-2-1" }`. It creates a pending Pico transaction. `GET /api/transactions?page=0&size=20` requires `read:transactions` and lists newest first. The persisted requester identity comes from the verified access token's `sub` claim; a request-body identity is ignored. `GET /api/admin/audit-events` and `GET /api/admin/status` require `admin:platform`. The existing `/api/device/transactions` and `/api/device/authorize` routes continue to use `X-Bridge-Token` independently of dashboard JWT permissions.

GET /api/admin/status requires an Auth0 access token with permissions=["admin:platform"]. The bridge credential cannot access admin routes. Legacy challenge/approval endpoints exist only with `dev,simulator` and require `X-Bridge-Token`. Never put that token in the browser. Bridge credential and device HMAC secret are distinct.

GET /api/admin/audit-events requires the same Auth0 `admin:platform` permission. It returns Spring Data pagination metadata and audit records newest first (timestamp, then ID descending); `page` defaults to 0 and `size` to 20 (maximum 100). Optional `deviceId`, `transactionId`, `eventType`, `outcome`, `reason`, `from`, and `to` filters combine with AND in the database before pagination. Enum values use exact names such as `DEVICE_AUTHORIZATION`, `REJECTED`, and `INVALID_SIGNATURE`. Timestamps are inclusive ISO-8601 values with timezone, such as `2026-10-17T12:00:00Z`. Invalid values or reversed dates return 400. For example, in PowerShell:

```powershell
$query = 'deviceId=slab-01&outcome=REJECTED&from=2026-10-17T00%3A00%3A00Z&page=0&size=20'
Invoke-RestMethod -Uri "http://localhost:8080/api/admin/audit-events?$query" -Headers @{ Authorization = "Bearer $env:AUTH0_ACCESS_TOKEN" }
```

Device authorization attempts store timestamp, event type, device ID, transaction ID, outcome, and a fixed reason code in PostgreSQL. Rejected attempts are committed, including invalid signatures, counter replays, expired/mismatched transactions, wrong glyphs, and locked-device attempts. Request bodies, signatures, HMAC keys, and bridge tokens are not stored in these records. No public audit read or unlock route exists.

Request limits use separate fixed-window quotas. `RUNEVAULT_RATE_WINDOW_SECONDS` sets the window (default 60 seconds), and `RUNEVAULT_RATE_MAX_ENTRIES` bounds each quota map (default 10000 identities). The per-window quotas are `RUNEVAULT_DASHBOARD_READS_PER_WINDOW` (300, per verified JWT `sub`), `RUNEVAULT_TRANSACTION_CREATION_PER_WINDOW` (60, per verified user or authenticated bridge identity), `RUNEVAULT_BRIDGE_AUTHORIZATIONS_PER_WINDOW` (120, bridge identity), `RUNEVAULT_BRIDGE_OTHER_PER_WINDOW` (120, bridge lookups and lore), `RUNEVAULT_ADMIN_OPERATIONS_PER_WINDOW` (60, per verified admin `sub`), and `RUNEVAULT_UNAUTHENTICATED_PER_WINDOW` (60, per direct client address). Expired entries are removed and capacity is bounded; requests over quota receive HTTP 429 with `Retry-After`. Health is exempt. Forwarded-IP headers are ignored, so a trusted reverse proxy would need explicit configuration before its client addresses could be used. These in-memory quotas apply **per backend instance**, not globally across replicas. The former process-wide `RUNEVAULT_REQUESTS_PER_MINUTE` setting is no longer used.

## Next B tasks
1. Flyway migrations and production-grade tables: devices (key version, revoked, organization), challenges (bound intent, expiry, consumed), transactions (unique challenge, state, version), audit_events (actor/identity). The demo currently relies on Hibernate schema updates.
2. Atomic database consumption and unique execution constraint for the older challenge/approval flow; test concurrent approvals and restart behavior.
3. Require independent approver identity for real transfers. Dashboard pending transactions record the verified Auth0 subject, but Pico approvals are still device-only.
4. Device key rotation/revocation, configurable allowed destinations and limits; expose changes only through admin permissions.
5. AWS account/region and Auth0 tenant configuration, then secrets, RDS, deployment, alarms. No infrastructure has been provisioned.

## Threat notes
Compromised bridge can request arbitrary challenges; physical buttons alone do not show the amount or destination. Add a trusted display or confirm details through a separate trusted channel. Local simulator contains a public test key and is never production signing infrastructure. The older challenge/approval flow still has in-memory replay state and permits one replica only. Device audit events are durable, but banking idempotency is not implemented. Public-key support must wait for A's agreed algorithm and encoding.

References: https://docs.spring.io/spring-boot/3.4/reference/web/spring-security.html and https://developer.auth0.com/resources/code-samples/api/spring/basic-role-based-access-control
