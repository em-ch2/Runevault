# One-server deployment package (sandbox demo)

This package runs Caddy, Angular/Nginx, and Spring Boot on one Vultr Ubuntu server; Tiger Cloud supplies PostgreSQL. Only Caddy publishes ports 80 and 443. The `auth,cloud` profiles preserve Auth0, Pico HMAC/counters, revocation, Gemini fail-closed decisions, the single `TransactionProcessor` execution claim, Nessie sandbox outcomes, and audits. `prod` remains deliberately blocked by `SecurityConfig`: this is **not** real-money production banking. The hardware simulator is disabled. Nessie is sandbox banking; the Pico provides physical authorization.

## Required inputs

- Vultr Ubuntu server, SSH key, repository read access, and domain.
- Tiger Cloud host, database, user, password, and verified CA certificate.
- The registered Pico device ID/key, matching private bridge token, Gemini key, Nessie sandbox key/source account/destination UUIDs, and chosen policy limits. Do not use repository demo credentials.
- Auth0 tenant with API audience `https://api.runevault`, RS256 and required RBAC permissions. The audience stays fixed; it is not the hosting URL.

The frontend Auth0 SDK redirects to `window.location.origin`. In Auth0 application settings add `https://YOUR_DOMAIN` to **Allowed Callback URLs**, **Allowed Logout URLs**, and **Allowed Web Origins**. Its client ID/domain are public identifiers embedded in the frontend; verify they refer to the intended tenant.

## Prepare Ubuntu

From a trusted machine:

```bash
ssh -i ~/.ssh/YOUR_PRIVATE_KEY ubuntu@YOUR_SERVER_IP
```

On Ubuntu, install Docker Engine and Compose plugin from Docker's apt repository. Review [Docker's current Ubuntu instructions](https://docs.docker.com/engine/install/ubuntu/) for your release:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker compose version
```

Clone through an SSH deploy key or another private read-only credential. Do not put credentials in a URL:

```bash
git clone git@github.com:YOUR_ORG/Swamphacks.git
cd Swamphacks
install -m 600 .env.cloud.example .env.cloud
nano .env.cloud
install -m 755 -d deploy/certs
install -m 644 /PATH/TO/VERIFIED_TIGER_CA.pem deploy/certs/tiger-ca.pem
```

Replace every placeholder in `.env.cloud`; retain the registered Auth0 audience. Keep `.env.cloud` and the CA out of Git. Obtain and verify the CA independently. `sslmode=verify-full` checks the database certificate and hostname, so the JDBC URL must use Tiger's certificate hostname. A mounted CA is supported via `sslrootcert`; do not downgrade to `sslmode=require`. See [pgJDBC SSL documentation](https://jdbc.postgresql.org/documentation/ssl/).

Cloud startup keeps Hibernate at `validate` and SQL initialization at `never`. Flyway applies the versioned application-table migrations first. Read [the database migration procedure](../docs/database-migrations.md), review the target schema and backup before first startup, and confirm the migration history afterward. A fresh Tiger database receives the application tables; importing historical local data is a separate reviewed operation. Person C's `pipeline_events` and TimescaleDB aggregates are not included yet.

## Validate and start

Do not run `docker compose config` without `--quiet`, because resolved output contains secrets:

```bash
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml config --quiet
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml build
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml run --rm --no-deps -e RUNEVAULT_DOMAIN=example.invalid caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml up -d
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml ps
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml logs --tail=100 backend caddy frontend
curl --fail https://YOUR_DOMAIN/api/health
```

Caddy obtains HTTPS certificates after DNS and inbound 80/443 work. It redirects HTTP to HTTPS, forwards `/api/*` unchanged to Spring Boot, and sends other paths to Nginx with Angular SPA fallback. `/ws/transfers` proxies ticket-authenticated WebSocket upgrades to Spring Boot. Set `RUNEVAULT_WS_ALLOWED_ORIGINS` to the exact deployed HTTPS origin. Caddy access logging is off so URLs, query strings, and credential headers do not enter access logs; keep application logs private. `/actuator/*` is blocked by Caddy. Backend readiness remains on the Compose network. Only `/api/health` is intended as a public unauthenticated check; dashboard and bridge security remain intact.

Set a DNS A record for `YOUR_DOMAIN` to the server's public IPv4 address, adding AAAA only if IPv6 works. Permit inbound TCP 80/443; restrict SSH TCP 22 to your own IP where practical. Do not open 8080, 4200, 5432, or 5433 in Vultr firewall or UFW; Compose publishes none of them. Limit Tiger Cloud access to the server's outbound address if supported.

Before public testing, manually verify the Auth0 application's deployed callback and logout URLs and allowed web origin are exactly `https://YOUR_DOMAIN`; confirm the registered API audience remains `https://api.runevault`, RS256 and RBAC permissions are enabled, and test users have appropriate roles. In the Auth0 dashboard, verify MFA policy and enrollment for the administrator account, then exercise login, logout, and an admin-only request over HTTPS. These tenant settings cannot be verified from this repository alone and are **unverified** until someone with tenant access checks them. Also verify DNS resolution, certificate issuance, Caddy's `/api/health`, blocked public `/actuator/health`, and bridge authentication without a token before using hardware remotely.

Inspect safely:

```bash
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml ps
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml logs --tail=100 backend
curl -I https://YOUR_DOMAIN/login
curl --fail https://YOUR_DOMAIN/api/health
```

Docker Compose `unhealthy` alone does not restart a container. Diagnose readiness, Tiger TLS/schema and application logs; restart after correcting the cause.

For a remote USB bridge, privately set `RUNEVAULT_API_URL=https://YOUR_DOMAIN`, choose its `RUNEVAULT_SERIAL_PORT`, and provide the **matching** `RUNEVAULT_BRIDGE_TOKEN` in its private environment. Check the bridge `.env` precedence in `bridge/README.md` before testing. Run `python bridge/usb_bridge.py --tx-id EXISTING_PENDING_ID` on the hardware computer. Never put a key or token in a URL.

## Updates and recovery

```bash
git pull --ff-only
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml build
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml up -d
sudo docker compose --env-file .env.cloud -f compose.cloud.yaml stop
```

Back up Tiger Cloud independently using its supported snapshots/backups and test restoration into a separate database. Application rollback means checking out a reviewed earlier commit and rebuilding images; it does **not** roll back the database. Review schema compatibility first. Never restore stale state over newer device counters or revocations: replay protection depends on monotonic persistent counters, and banking claims/outcomes must not be replayed. Keep Caddy's certificate volumes during routine updates. A fresh empty demo database needs fresh device provisioning; it is not a continuation of local history.
