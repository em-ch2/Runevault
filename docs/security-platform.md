# Security platform: first implementation

## Profiles
- Default `dev`: bridge token required on challenge/approval endpoints; health public; other endpoints denied.
- `auth`: same bridge gate plus Auth0 JWT validation for dashboard API routes. Admin status requires `admin:platform` permission. Set SPRING_PROFILES_ACTIVE=auth, AUTH0_ISSUER_URI (HTTPS issuer with trailing slash), AUTH0_AUDIENCE, RUNEVAULT_BRIDGE_TOKEN (random 32+ bytes), and RUNEVAULT_DEVICE_SECRET.
- `prod`: deliberately refuses startup. Persistent replay and execution storage are not ready. Do not bypass this guard to deploy real transfers.

Auth0 setup: create an API with the audience identifier above; enable RBAC and Add Permissions in the Access Token. Add admin:platform permission and assign it through an administrator role. Frontend owner implements Auth0 authorization-code login with PKCE and sends access tokens, not ID tokens. Enable MFA in the Auth0 tenant; this repository does not configure tenant policy or implement browser login.

GET /api/admin/status requires an Auth0 access token with permissions=["admin:platform"]. The bridge credential cannot access admin routes. Challenge/approval endpoints require X-Bridge-Token, even in dev. Never put that token in the browser. Bridge credential and device HMAC secret are distinct.

GET /api/admin/audit-events requires the same Auth0 `admin:platform` permission. It returns Spring Data pagination metadata and audit records newest first; `page` defaults to 0 and `size` to 20 (maximum 100). For example, `/api/admin/audit-events?page=0&size=20`. Device authorization attempts store timestamp, event type, device ID, transaction ID, outcome, and a fixed reason code in PostgreSQL. Rejected attempts are committed, including invalid signatures, counter replays, expired/mismatched transactions, wrong glyphs, and locked-device attempts. Request bodies, signatures, HMAC keys, and bridge tokens are not stored in these records. No public audit read or unlock route exists.

Current rate limiter is a configurable process-wide fixed window: RUNEVAULT_REQUESTS_PER_MINUTE (default 120), with HTTP 429 and Retry-After. Health is exempt. A noisy client can exhaust this shared quota; replace with gateway/per-principal limits before public deployment. Do not trust forwarded client-IP headers without a configured trusted proxy.

## Next B tasks
1. Flyway migrations and production-grade tables: devices (key version, revoked, organization), challenges (bound intent, expiry, consumed), transactions (unique challenge, state, version), audit_events (actor/identity). The demo currently relies on Hibernate schema updates.
2. Atomic database consumption and unique execution constraint for the older challenge/approval flow; test concurrent approvals and restart behavior.
3. Requester identity from verified Auth0 subject; require independent approver identity. Never accept requester identity from an unsigned request field.
4. Device key rotation/revocation, configurable allowed destinations and limits; expose changes only through admin permissions.
5. AWS account/region and Auth0 tenant configuration, then secrets, RDS, deployment, alarms. No infrastructure has been provisioned.

## Threat notes
Compromised bridge can request arbitrary challenges; physical buttons alone do not show the amount or destination. Add a trusted display or confirm details through a separate trusted channel. Local simulator contains a public test key and is never production signing infrastructure. The older challenge/approval flow still has in-memory replay state and permits one replica only. Device audit events are durable, but banking idempotency is not implemented. Public-key support must wait for A's agreed algorithm and encoding.

References: https://docs.spring.io/spring-boot/3.4/reference/web/spring-security.html and https://developer.auth0.com/resources/code-samples/api/spring/basic-role-based-access-control
