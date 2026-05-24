# Ground Control — Deployment

## Local Development

### Prerequisites

- Java 21 (Eclipse Temurin recommended)
- Docker Engine 24+ and Docker Compose v2
- Gradle wrapper included (`backend/gradlew`) — no global Gradle install needed

### Setup

```bash
git clone https://github.com/KeplerOps/Ground-Control.git
cd Ground-Control
cp .env.example .env
make up                            # Start PostgreSQL + AGE
make rapid                         # Format + compile (~1s with warm daemon)
make dev                           # Spring Boot dev server on :8000
```

Flyway migrations run automatically on application startup — there is no separate migration step.

### Docker Compose Services

The `docker-compose.yml` in the project root runs infrastructure only. Spring Boot runs on the host.

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| `db` | `apache/age:release_PG16_1.6.0` | 5432 | Primary database (PostgreSQL 16 + Apache AGE 1.6.0) |
| `redis` | `redis:7` | 6379 | Available for future use (not used by the application currently) |

PostgreSQL data persists in the `gc-postgres-data` named volume.

### Environment Variables

All settings use the `GC_` prefix. See `.env.example`.

| Variable | Default | Description |
|----------|---------|-------------|
| `GC_DATABASE_URL` | `jdbc:postgresql://localhost:5432/ground_control` | JDBC connection URL |
| `GC_DATABASE_USER` | `gc` | Database username |
| `GC_DATABASE_PASSWORD` | `gc` | Database password |
| `GC_REDIS_URL` | `redis://localhost:6379` | Redis connection URL (unused by app currently) |
| `GC_SERVER_PORT` | `8000` | HTTP server port |
| `GC_AGE_ENABLED` | `false` | Enable Apache AGE graph queries |
| `GC_SWEEP_ENABLED` | `false` | Enable scheduled analysis sweeps |
| `GC_SWEEP_CRON` | `0 0 6 * * *` | Cron expression for sweep schedule |
| `GC_EMBEDDING_PROVIDER` | `none` | Embedding provider (`openai` or `none`) |
| `GC_EMBEDDING_API_KEY` | _(empty)_ | API key for the embedding provider |
| `GC_EMBEDDING_API_URL` | `https://api.openai.com/v1` | Embedding API base URL |
| `GC_EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding model identifier |
| `GC_EMBEDDING_DIMENSIONS` | `1536` | Embedding vector dimensions |
| `GC_EMBEDDING_BATCH_SIZE` | `100` | Max texts per batch embedding request |
| `GC_EMBEDDING_SIMILARITY_THRESHOLD` | `0.85` | Default cosine similarity threshold |
| `GC_SECURITY_ENABLED` | `true` | Master switch for the API access control layer (ADR-026, GC-P011). The `dev` and `test` profiles override this to `false`. |
| `GC_SECURITY_OPENAPI_PUBLIC` | `false` | When `true`, `/api/openapi.json` and `/api/docs/**` are reachable anonymously. Otherwise authenticated. |
| `GROUNDCONTROL_SECURITY_CREDENTIALS_<N>_PRINCIPAL_NAME` | _(unset)_ | Principal name written to audit fields when this token authenticates. |
| `GROUNDCONTROL_SECURITY_CREDENTIALS_<N>_TOKEN` | _(unset)_ | Bearer token presented as `Authorization: Bearer <token>`. |
| `GROUNDCONTROL_SECURITY_CREDENTIALS_<N>_ROLE` | _(unset)_ | `USER` or `ADMIN`. Admin roles are required for `/api/v1/admin/**`, `/api/v1/embeddings/**`, `/api/v1/analysis/sweep/**`, `/api/v1/pack-registry/**`. |
| `GROUNDCONTROL_SECURITY_IP_ALLOWLIST_<N>` | _(unset)_ | Optional CIDR. Empty list = no IP gating. `X-Forwarded-For` is NOT honored — proxies must terminate the IP gate or set the source IP via `RemoteIpFilter`. |

### Authentication & Network Access (ADR-026)

Production deployments MUST configure `groundcontrol.security.credentials`
(or supply equivalent `GROUNDCONTROL_SECURITY_CREDENTIALS_*` env vars).
With `GC_SECURITY_ENABLED=true` and an empty credential list, every
authenticated route returns 401.

Authority matrix:

| Path | Required authority |
|------|--------------------|
| `/actuator/health`, `/actuator/info` | anonymous |
| `/error` | anonymous |
| `/api/openapi.json`, `/api/docs/**`, `/v3/api-docs/**`, `/swagger-ui/**` | gated by `GC_SECURITY_OPENAPI_PUBLIC` |
| `/api/v1/admin/**` | `ROLE_ADMIN` |
| `/api/v1/embeddings/**` | `ROLE_ADMIN` |
| `/api/v1/analysis/sweep/**` | `ROLE_ADMIN` |
| `/api/v1/pack-registry/**` | `ROLE_ADMIN` |
| `/api/v1/trust-policies/**` | `ROLE_ADMIN` |
| `/api/v1/pack-install-records/**` | `ROLE_ADMIN` |
| Other `/api/v1/**` | authenticated (USER or ADMIN) |
| `/login`, `/logout`, and static assets needed to render the login/app shell | anonymous |
| SPA shell (`/`, `/index.html`) and SPA client routes | authenticated browser session; unauthenticated navigation redirects to `/login` |
| Anything else | denyAll |

Clients send `Authorization: Bearer <token>` on every request. Tokens are
compared with `MessageDigest.isEqual` for constant-time compare; rotation
is a config edit + restart.

Migrating from pre-#243 deployments:

- `ground-control.pack-registry.security.admin-credentials` is removed.
  Move admin entries into `groundcontrol.security.credentials` with
  `role: ADMIN`. The pack-signing block (`trusted-signers`) is unchanged.
- The `X-Actor` header is no longer a self-service identity claim when
  security is enabled; the authenticated principal name is used for audit.

### Web UI login (ADR-037)

A second filter chain ships alongside the bearer chain for browser users
(see ADR-037). Both modes populate the same `SecurityContext` principal +
`ROLE_USER` / `ROLE_ADMIN` authorities, so the path matrix above applies
to either authentication mode.

Session cookie hardening is **hardcoded** in `application.yml`; there are no
deploy-time env knobs for these values. Changing them requires an ADR + security
review, not an `.env` flag.

| Property | Value |
|---|---|
| `server.servlet.session.cookie.name` | `GC_SESSION` |
| `server.servlet.session.cookie.http-only` | `true` |
| `server.servlet.session.cookie.secure` | `true` (`false` in dev/test profiles only) |
| `server.servlet.session.cookie.same-site` | `strict` |
| `server.servlet.session.timeout` | `60m` |

CSRF protection is on for the browser chain (Spring's
`CookieCsrfTokenRepository` with `HttpOnly=false`, so the SPA's
`fetch` wrapper can read the `XSRF-TOKEN` cookie and echo it via the
`X-XSRF-TOKEN` header). The bearer chain remains CSRF-disabled —
agents do not need to send a CSRF token.

#### First-admin bootstrap

A fresh install has zero users in the JDBC store, so the first admin
must be created out-of-band. Run the bootstrap on the deployment host:

**Preferred — mode-600 password file** (works headless, no secret in any
command line):

```bash
install -m 600 /dev/null /run/gc/admin-password
read -rs -p 'Admin password: ' adminpw && printf '%s' "$adminpw" > /run/gc/admin-password && unset adminpw
./gradlew bootRun --args="--create-admin --username=admin --password-file=/run/gc/admin-password"
shred -u /run/gc/admin-password  # or rm; the runner has finished reading
```

The runner refuses any file that is group/others-readable OR
group/others-writable on a POSIX filesystem, so the `chmod 600` (via
`install -m 600`) is mandatory, not advisory.

**TTY prompt** (interactive operator at a console — the password is
read with echo off and never lands on a command line):

```bash
./gradlew bootRun --args="--create-admin --username=admin"
# Prompts: "Admin password: "
```

**Env-var path** is only safe when the value is supplied out-of-band by
a secret manager (systemd `LoadCredential=`, AWS / GCP Secret Manager,
HashiCorp Vault agent, Kubernetes mounted secret env). **Do not** put
the value inline on the command line — that leaks through shell
history, terminal recorders, and CI logs the same way `--password=...`
would. For ad-hoc operator runs prefer the file or TTY paths above.

The runner is idempotent — running it a second time with the same
username logs a "user already present" message and exits 0.

> **Do not** pass the password via `--password=...` argv. The bootstrap
> runner refuses that path and exits non-zero. Argv-supplied secrets
> leak through shell history, `/proc/<pid>/cmdline`, process listings,
> CI logs, and agent transcripts. ADR-037 §5 documents the rationale.

Subsequent admins are created via the session/CSRF curl flow below.
Ground Control's SPA shell does not include a user-management page in
this iteration — the REST endpoints under `/api/v1/admin/users` are the
single supported surface. The bearer chain refuses non-`Bearer`
schemes, so HTTP Basic against `/api/v1/**` is not an option; a
session login is required.

```bash
# 0. Restrict cookie/jar visibility before any session is written. umask 077
#    ensures the jar created by curl below is mode 600, and the explicit chmod
#    handles the rare case where umask was overridden by a parent shell setting.
umask 077
jar="$(mktemp /tmp/gc-jar.XXXXXX)"
chmod 600 "$jar"
trap 'rm -f "$jar"' EXIT

# 1. Pick up the CSRF cookie.
curl -sS -c "$jar" "$BASE/login" -o /dev/null

# 2. Submit the form login. `--data-urlencode "name@/path"` (no `=` sign)
#    reads the field value from the file; the URL-encoded body keeps the
#    password out of argv and out of the request URL.
csrf="$(awk '$6=="XSRF-TOKEN"{print $7}' "$jar")"
curl -sS -b "$jar" -c "$jar" \
  --data-urlencode "username=admin" \
  --data-urlencode "password@/run/gc/admin-password" \
  --data-urlencode "_csrf=$csrf" \
  -o /dev/null -X POST "$BASE/login"

# 3. Create the user with the session cookie + CSRF header. The JSON body
#    comes from a mode-600 file so the new account's password also stays
#    out of argv / shell history / CI logs.
csrf="$(awk '$6=="XSRF-TOKEN"{print $7}' "$jar")"
curl -sS -b "$jar" -X POST "$BASE/api/v1/admin/users" \
  -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $csrf" \
  --data @/run/gc/new-user.json
```

The cookie jar carries `GC_SESSION` (live until logout / timeout) and
`XSRF-TOKEN`; both are sensitive. The `trap` cleans the jar on exit, and
`umask 077` + explicit `chmod 600` guarantee a local unprivileged user
cannot snapshot the session before it expires. `--data-urlencode
"password@/path"` is curl's file-read form (no `=` between field name
and `@`); `--data-urlencode "password=@/path"` would submit the literal
string `@/path` as the password. The same flow drives the MCP
`gc_user_admin` actions (list / update_role / update_enabled /
delete_user); MCP intentionally does not accept passwords as tool
arguments, so user creation always goes through this curl path.

The last enabled admin cannot be demoted, disabled, or deleted — the
service returns `409 last_admin` and the operation is refused. Provision
a second admin before retiring the first.

### Authenticated red-dragon redeploy guardrails

Rolling a pre-ADR-026 deployment forward to an ADR-026 image is an
authentication cutover, not just an image update. Before a security-enabled
image is deployed to red-dragon, every existing consumer of
`http://red-dragon:8000/api/v1/**` must be inventoried and configured with a
Bearer token. This includes repo-local MCP servers, agent `.mcp.json` entries,
GitHub Actions live-policy or pack-sync jobs, ad-hoc `curl` / `gh api` scripts,
and any long-running agent process that inherited `GC_BASE_URL`.

Use the existing ADR-026 credential model only:

```env
GC_SECURITY_ENABLED=true
GROUNDCONTROL_SECURITY_CREDENTIALS_0_PRINCIPAL_NAME=ground-control-mcp
GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKEN=...
GROUNDCONTROL_SECURITY_CREDENTIALS_0_ROLE=USER
GROUNDCONTROL_SECURITY_CREDENTIALS_1_PRINCIPAL_NAME=operator-admin
GROUNDCONTROL_SECURITY_CREDENTIALS_1_TOKEN=...
GROUNDCONTROL_SECURITY_CREDENTIALS_1_ROLE=ADMIN
```

The token values live only in operator-managed secrets or `/opt/gc/.env`
(mode 600), never in git or transcripts. MCP consumers should receive the
appropriate value as `GROUND_CONTROL_API_TOKEN`; admin-only pack-registry
automation may continue to use `GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN`
because the repo-local MCP client maps it onto the unified ADR-026 bearer
scheme.

`/opt/gc/docker-compose.yml` is a runtime mirror, not the source of truth.
Any backend environment passthrough needed for ADR-026 credentials belongs
first in `deploy/docker/docker-compose.prod.yml`; after a clean sync,
the compose file at `/opt/gc/docker-compose.yml` should match that repo file
byte-for-byte. Operator-local secret material remains only in `/opt/gc/.env`.

The production compose file publishes the backend on `${GC_BIND_IP:-0.0.0.0}:8000:8000`.
On red-dragon, set `GC_BIND_IP=100.98.28.66` (the host's tailnet IP) in
`/opt/gc/.env` so docker-proxy listens only on the tailnet interface — public
IPv4 / IPv6 attempts to reach the API never even establish a TCP connection
to the proxy. Defense in depth on top of ADR-026 bearer auth: even if the
backend has a future auth bypass, an attacker would need a tailnet identity
to reach it.

A second, host-firewall layer drops TCP packets that arrive on the public
interface bound for port 8000, regardless of what docker-proxy is listening
on. The systemd unit lives at `deploy/scripts/gc-firewall.service`; install
it once per host:

```bash
sudo install -o root -g root -m 644 \
  deploy/scripts/gc-firewall.service /etc/systemd/system/gc-firewall.service
sudo systemctl daemon-reload
sudo systemctl enable --now gc-firewall.service
```

Verify with `sudo iptables -L INPUT -n -v | head` — rule 1 should be
`DROP -i enp8s0 tcp dport 8000`. The unit reads the public interface name
from the rule itself (`enp8s0` matches red-dragon); change the unit if your
deployment uses a different public NIC. Tailnet traffic enters via
`tailscale0` (or via `lo` when the host talks to itself by its tailnet IP)
and is unaffected.

Both layers are idempotent and stateless — re-running `up -d` or
`systemctl restart gc-firewall` is safe.

### Migrating an existing deployment to ADR-026 auth

Use this playbook when rolling forward from a pre-ADR-026 image (every
deployed image before the V055-V058 / threat-model wave) to a security-enabled
image. The 2026-05-09 attempt failed by skipping steps 1-4: the image was
rolled in before consumers had tokens, and every in-flight agent caller 401'd
within seconds. Order matters; do not skip ahead.

1. **Inventory every consumer of `http://red-dragon:8000/api/v1/**`.** Sweep:
   - This repo's MCP server (`mcp/ground-control/lib.js`) and any
     agent-side `.mcp.json` entry that points at `red-dragon`.
   - GitHub Actions live-policy / pack-sync jobs (`policy-live`,
     `scripts/pack-sync.sh`, `tools/ground_control/check_live_policy.mjs`,
     `tools/ground_control/check_adr_drift.mjs`,
     `tools/ground_control/sync_policy.mjs`,
     `tools/packs/sync_packs.mjs`).
   - Long-running agent processes that inherited `GC_BASE_URL` from the
     operator's shell (each one needs a token of its own logical class —
     consumer-classes share a single token, individual processes do not
     each need a unique one).
   - Ad-hoc `curl`, `gh api`, or scripted callers.
   Every entry on the list needs a token before step 6 happens.

2. **Provision tokens — one principal per logical consumer-class.** Generate
   long random tokens (e.g. `openssl rand -hex 32`). Typical layout:
   - slot 0: `ground-control-mcp` / `USER` — every MCP / agent caller.
   - slot 1: `operator-admin` / `ADMIN` — the human operator's CLI.
   - slot 2: `automation` / `ADMIN` — CI live-policy + pack-sync jobs.
   Reserve slots 3-4 for unforeseen consumers without re-editing the compose
   file. Tokens NEVER land in git or transcripts.

3. **Distribute tokens to each consumer.** Pick one mechanism and stay
   consistent with the existing `${SONAR_TOKEN}` precedent:
   - Env-var inheritance via `${GROUND_CONTROL_API_TOKEN}` substitution in
     `.mcp.json`. Operator sets `GROUND_CONTROL_API_TOKEN` in their shell
     and `claude`/agent processes inherit it.
   - Operator secret store (1Password, `pass`, etc.) read by each agent's
     start-up script.
   For GitHub Actions, both:
   (a) store the token as a repo secret (`GROUND_CONTROL_API_TOKEN`); and
   (b) inject it into each step that talks to Ground Control via
       `env: GROUND_CONTROL_API_TOKEN: ${{ secrets.GROUND_CONTROL_API_TOKEN }}`
       on the workflow step (already wired into `policy-live` in
       `.github/workflows/ci.yml`; any new live-API workflow step must do the
       same).
   The pre-existing `GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN` continues to
   work for pack-sync since `mcp/ground-control/lib.js` maps it onto the
   unified ADR-026 bearer scheme.

4. **Verify each consumer is actually sending its bearer token — not just
   that it can reach the API.** A `200` against the pre-ADR-026 production
   image only proves reachability; a consumer with no `Authorization` header
   at all also returns `200` there, then immediately `401` after cutover.
   The root `docker-compose.yml` uses `SPRING_PROFILES_ACTIVE=dev`, which
   sets `groundcontrol.security.enabled=false` — running consumers against
   that local stack would also produce a false green for the same reason.
   Use one of these dry-runs instead:
   - **Security-enabled local instance using the candidate ADR-026 image.**
     The dry-run MUST use the candidate image (the one targeted by the
     cutover) — running the pre-ADR image locally reproduces the
     reachability-only false green this step is designed to prevent.

     **Resolving the candidate image.** `:latest` only updates on push to
     `main`, so post-merge-to-`dev` it is still the previous release. Use
     `:dev` (head of unreleased work, tracks dev tip) or `:sha-<commit>`
     (immutable) to refer to the actual candidate. Resolve a stable digest
     from either with:
     ```bash
     docker pull ghcr.io/keplerops/ground-control:dev
     docker inspect ghcr.io/keplerops/ground-control:dev \
       --format '{{index .RepoDigests 0}}'
     # → ghcr.io/keplerops/ground-control@sha256:<digest>
     ```
     Pin the dry-run AND the production `/opt/gc/.env` to that digest so
     the image you tested is the image you deploy.

     **Isolating the dry-run database.** The production compose file's
     `db` service bind-mounts `/data/postgres/`. To keep prod's schema
     untouched, run the dry-run with a separate compose project name and
     swap the bind-mount for an isolated Docker named volume. Use a
     non-default port (e.g. `18000:8000`) so it does not collide with the
     running production backend on `:8000`. Tear down with `down -v` to
     destroy the throwaway DB volume when finished.
     ```bash
     # mktemp + chmod 600 so the scratch env file is never world-readable
     # while it carries draft credential token values.
     dryrun_env="$(mktemp -t gc-prod-dryrun.env.XXXXXX)"
     chmod 600 "${dryrun_env}"
     cp deploy/docker/.env.template "${dryrun_env}"
     # Edit ${dryrun_env} to fill GC_DATABASE_PASSWORD, POSTGRES_PASSWORD,
     # and GROUNDCONTROL_SECURITY_CREDENTIALS_<N>_* slots with the same
     # token values that will land in /opt/gc/.env. Set GC_IMAGE to the
     # candidate digest you resolved above. Run with a separate compose
     # project name (gc-dryrun) and rebind the prod port + DB volume:
     GC_IMAGE=ghcr.io/keplerops/ground-control@sha256:<digest> \
       docker compose -p gc-dryrun --env-file "${dryrun_env}" \
       -f deploy/docker/docker-compose.prod.yml up -d
     # When done:
     docker compose -p gc-dryrun --env-file "${dryrun_env}" \
       -f deploy/docker/docker-compose.prod.yml down -v
     shred -u "${dryrun_env}"
     ```
     Point each consumer at `http://localhost:8000` and confirm each
     returns `200` against `/api/v1/projects`. The compose file declares
     the indexed credential slots as list-form passthroughs, so unset
     slots are NOT injected into the container and Spring sees only the
     populated entries. A green dry-run here proves token delivery
     end-to-end against the same auth layer production will run after
     cutover.
   - **Server-side audit-log inspection.** Make each consumer issue one
     known authenticated call against the dry-run instance, then read the
     backend's structured logs and confirm the request appears with the
     expected principal name (`ground-control-mcp`, `operator-admin`,
     `automation`). The principal name comes from `ActorFilter` /
     `ActorHolder` and is logged via the per-request MDC key `actor_id`
     (matching `logback-spring.xml`'s production JSON appender), NOT the
     bearer token itself. This proves token delivery without the token
     ever leaving the server-side process boundary, so the captured
     output is safe to keep around for review:
     ```bash
     docker compose -f deploy/docker/docker-compose.prod.yml \
       --env-file "${dryrun_env}" logs --tail=200 backend | \
       grep '"actor_id"'
     ```
   Do NOT capture wire-level traces (e.g. `curl --trace-ascii`,
   `tcpdump`, agent debug stdout) for this verification: those formats
   embed the literal `Authorization: Bearer <token>` header, and any
   resulting file or transcript becomes a credential leak — anyone who
   can read the saved capture can replay the token. Status-code +
   server-side principal-name verification covers the same property
   without that exposure.

   A consumer that gets `200` against the pre-ADR-026 production but fails
   the candidate-image dry-run is exactly the regression this step
   prevents — its bearer header is missing or wrong, and it will 401 the
   moment the new image starts.

   **Important precedence note:** `SPRING_APPLICATION_JSON` is also
   forwarded to the container (legacy support for the pack-registry
   bootstrap path in `deploy/scripts/enable_pack_registry_auth.sh`). When
   it carries a `groundcontrol.security.credentials` block, Spring
   *replaces* the indexed env-var list rather than merging — the new env
   vars are silently ignored. Before the cutover, confirm
   `SPRING_APPLICATION_JSON` either does NOT include
   `groundcontrol.security.credentials` at all, or contains the same
   principals you provisioned via the indexed env vars. The dry-run
   above will surface this if it bites: a consumer that authenticates
   under indexed env vars but fails inside the dry-run container is
   most likely losing to a stale `SPRING_APPLICATION_JSON` block.

5. **`pg_dump -Fc` snapshot.** Defensive — the new image carries V055-V058
   forward-only migrations:
   ```bash
   ssh red-dragon /opt/gc/backup.sh
   # OR (if running on red-dragon directly):
   docker exec gc-db-1 pg_dump -Fc -U gc ground_control \
     > /data/backups/gc-pre-cutover-$(date -u +%Y%m%dT%H%M%SZ).dump
   ```

6. **Update `/opt/gc/.env` with the credential block + the candidate
   digest.** Use the indexed `GROUNDCONTROL_SECURITY_CREDENTIALS_*` shape
   (matches the env-var table above and `deploy/docker/.env.template`).
   The same digest you dry-ran against in step 4 belongs in `GC_IMAGE`
   here — pinning by digest (`ghcr.io/keplerops/ground-control@sha256:...`)
   guarantees the cutover rolls the image you tested, not whatever has
   moved under `:dev` since. After editing, `chmod 600`.

7. **Sync `/opt/gc/docker-compose.yml` byte-for-byte from this repo's
   `deploy/docker/docker-compose.prod.yml`.** That file is the canonical
   source of the env-passthrough block; the runtime copy must match it
   after sync, otherwise the next sync clobbers operator-local edits.
   The `make policy` gate
   (`tools/policy/checks.py::run_deploy_compose_credential_passthrough`)
   enforces the credential keys stay present in the canonical file.

8. **Roll the image.** Pick one path:
   - **Manual (recommended for the first cutover).** From red-dragon (or
     any tailnet host with the deploy SSH key):
     ```bash
     cd /opt/gc
     docker compose --env-file .env up -d --force-recreate backend
     # OR via the SSH forced-command target:
     ssh gc-deploy@red-dragon
     ```
   - **CI auto-deploy.** Merge `dev` → `main`. The `deploy` job in
     `.github/workflows/ci.yml` runs `/opt/gc/deploy.sh` over the
     fabricator-managed self-hosted runner pool's tailnet bridge. Only
     use this path AFTER steps 1-7 are confirmed; the deploy job has no
     awareness of consumer-token state and will happily 401 every
     consumer if you skip them.

9. **Verify Flyway and the threat-model surface.** `V055`-`V058` apply on
   first start of the new image; the `threat_model` table appears in the
   schema:
   ```bash
   docker compose exec db psql -U gc -d ground_control -c \
     "SELECT version FROM flyway_schema_history WHERE version IN ('55','56','57','58');"
   docker compose exec db psql -U gc -d ground_control -c "\\d threat_model"
   ```

10. **Re-verify every consumer from step 1 returns `200` (or its expected
    status, e.g. `404` for a not-found lookup) — never `401`.** Hit the
    threat-model MCP path explicitly (`gc_list_threat_models`,
    `gc_create_threat_model`, `gc_get_threat_model`) since that is the
    surface this whole migration was meant to enable. A `401` from any
    consumer at this point means the token was not delivered (back to
    step 3) — do NOT roll back the image; rollback is reserved for
    actual schema/data damage.

If any step fails, stop and surface the failure to the operator before
continuing — every later step assumes the earlier one succeeded.

### Makefile Targets

| Target | Description |
|--------|-------------|
| `make rapid` | Format + compile, no tests or static analysis (~1s warm) |
| `make build` | Build the project (no tests) |
| `make test` | Run unit tests (no static analysis) |
| `make test-cov` | Run tests with JaCoCo coverage report |
| `make format` | Format code with Spotless |
| `make lint` | Check formatting (Spotless) |
| `make check` | Full build + tests + static analysis + coverage (CI-equivalent) |
| `make integration` | Integration tests (Testcontainers) |
| `make verify` | Full verification: check + integration tests + OpenJML ESC |
| `make dev` | Start Spring Boot dev server on :8000 |
| `make up` | `docker compose up -d` |
| `make down` | `docker compose down` |
| `make docker-build` | Build production Docker image |
| `make smoke` | Build Docker image and verify Flyway + health endpoint |
| `make clean` | Remove build artifacts |

### Production Docker Image

The backend ships as a multi-stage Docker image (`backend/Dockerfile`):

- **Builder stage**: Eclipse Temurin JDK 21 Alpine, builds fat JAR with Gradle
- **Runtime stage**: Eclipse Temurin JRE 21 Alpine, runs as non-root user `gc`
- **Entrypoint**: `java -jar app.jar` on port 8000

#### Build locally

```bash
make docker-build
# or directly:
docker build -f backend/Dockerfile -t ghcr.io/keplerops/ground-control:latest .
```

#### Run locally

```bash
docker run --rm -p 8000:8000 \
  -e GC_DATABASE_URL=jdbc:postgresql://host:5432/ground_control \
  -e GC_DATABASE_USER=gc \
  -e GC_DATABASE_PASSWORD=gc \
  ghcr.io/keplerops/ground-control:latest
```

Flyway migrations run automatically on startup — no separate migration step needed.

#### Smoke test

```bash
make smoke
```

Builds the Docker image, starts a fresh PostgreSQL 16 container, runs the app against it, and verifies Flyway migrations apply and the health endpoint returns UP.

#### CI/CD

The `docker.yml` GitHub Actions workflow automatically builds and pushes to GHCR on:
- Push to `main` or `dev`
- Semver tags (`v*`)

CI (build, test, integration, verify) must pass before the image is built.

### Resetting

```bash
make down              # stop services, keep data
docker compose down -v # stop services, delete all data
```

---

## Hetzner Deployment (`red-dragon` + Tailscale)

Ground Control runs on `red-dragon` (Hetzner dedicated, AMD Ryzen 7 3700X / 128 GB / 2× 1 TB NVMe), accessible only via Tailscale. The full decision record is [ADR-030](../../architecture/adrs/030-on-prem-hetzner-deployment.md). [ADR-018](../../architecture/adrs/018-aws-ec2-deployment.md) is preserved as the historical AWS predecessor.

### Architecture

- **Host**: `red-dragon` (single tailnet-resident host, Ubuntu, Docker Engine 29.x, Compose v5.x).
- **Access**: Tailscale only. sshd binds to `100.98.28.66:22` (the tailnet IP) — no public ingress. Application reachable on tailnet at `http://red-dragon:8000`.
- **Storage**: `/data/postgres` (bind-mounted into the db container) and `/data/backups` (pg_dump artifacts). Both on red-dragon's main NVMe.
- **Image registry**: GHCR (`ghcr.io/keplerops/ground-control`). Pulled by `docker compose pull` on each deploy.
- **Backups**: 3×/day `pg_dump` cron to `/data/backups/`, 30-day local retention. Off-box copy via rsync over the tailnet to `aurora`. Policy is GC-P021 / [ADR-025](../../architecture/adrs/025-backup-policy.md).
- **Cost**: $0 marginal (red-dragon is paid for unrelated reasons).

### `/opt/gc/` layout

The compose stack on red-dragon lives at `/opt/gc/`:

| Path | Owner | Purpose |
|------|-------|---------|
| `/opt/gc/docker-compose.yml` | `atomik` | Compose stack (db + backend). Mirrors `deploy/docker/docker-compose.prod.yml` in this repo. |
| `/opt/gc/.env` | `atomik` (mode 600) | Environment file consumed by compose. Carries DB credentials, GHCR image reference, embedding API keys. Not in git. |
| `/opt/gc/deploy.sh` | `root` (mode 755) | Forced-command target invoked over SSH by the `gc-deploy` user. Pulls the latest image, brings the stack up, verifies `/actuator/health`. |
| `/data/postgres/` | `999:999` | Postgres data directory (bind-mount). |
| `/data/backups/` | `atomik` | pg_dump artifacts. |

### Deploy contract

#### Automatic

Every push to `main` that passes CI + smoke triggers the `deploy` job in `.github/workflows/ci.yml`. The job runs on a fabricator-managed runner (which joins the tailnet at first boot via [`KeplerOps/fabricator` PR #14](https://github.com/KeplerOps/fabricator/pull/14)) and SSHes `gc-deploy@red-dragon`. The `gc-deploy` user's `authorized_keys` carries a single forced-command entry:

```
command="/opt/gc/deploy.sh",restrict <ed25519-pubkey>
```

`restrict` disables PTY, port forwarding, X11, agent forwarding, user-rc — the deploy key cannot do anything except run the deploy script. SSH exit code is the deploy script's exit code.

The two GitHub repo secrets that drive this:

- `RED_DRAGON_DEPLOY_KEY` — the ed25519 private key for `gc-deploy`.
- `RED_DRAGON_KNOWN_HOSTS` — host-key fingerprints for `red-dragon` (`StrictHostKeyChecking=yes`).

The `tag:fabricator-runner` → `tag:gc-host:tcp:22` tailnet ACL constrains the runner's reach to the deploy port. No other tailnet device is reachable from a runner.

#### Manual

From any tailnet host:

```bash
# Deploy latest image
ssh gc-deploy@red-dragon                     # forced command runs deploy.sh

# Operator-side compose ops (run as your normal tailnet identity, not gc-deploy)
ssh red-dragon 'cd /opt/gc && docker compose ps'
ssh red-dragon 'cd /opt/gc && docker compose logs --tail=200 backend'
```

### Data Migration (historical)

The cutover from gc-dev (EC2) to red-dragon was performed 2026-05-03:

```bash
# Dump on gc-dev
ssh ec2-user@gc-dev 'sudo docker compose -f /opt/gc/docker-compose.yml --env-file /opt/gc/.env \
  exec -T db pg_dump -Fc -U gc ground_control' > /data/backups/gc-cutover.dump

# Restore on red-dragon
docker cp /data/backups/gc-cutover.dump gc-db-1:/tmp/cutover.dump
docker exec gc-db-1 pg_restore -U gc -d ground_control --clean --if-exists --no-owner --no-acl -j 4 /tmp/cutover.dump
```

Apache AGE extension state (`ag_graph`, `ag_label`) emits ignorable duplicate-key errors during restore — those tables are pre-populated by the AGE extension at db init. Application data restores cleanly.

### Backup and Recovery

The legacy AWS path (DLM snapshots + S3 dumps) is gone. The on-prem path is:

| Layer | Mechanism | Cadence | Retention |
|-------|-----------|---------|-----------|
| Local logical | `pg_dump -Fc` from inside the db container → `/data/backups/` | 3×/day (03/11/19 UTC) | 30 days local |
| Off-box logical | `rsync` over tailnet to `aurora:/var/backups/groundcontrol/` | After each pg_dump | per-aurora retention policy |
| Restore drill | `pg_restore` of the newest dump into a throwaway db | Weekly | log retained 30 days |

GC-P021 requires backup ≥ 3×/day with off-box durability. The `assert-backup-policy.sh` gate (pre-commit / `make policy`) enforces the policy floor on whatever values the repo declares; lowering cadence below 3×/day will fail the gate.

The operator runbook is [docs/operations/backup-restore.md](../operations/backup-restore.md). Manual ops:

```bash
ssh red-dragon /opt/gc/backup.sh                  # ad-hoc backup
ssh red-dragon /opt/gc/restore.sh --list          # enumerate
ssh red-dragon /opt/gc/restore.sh /data/backups/<name>.dump
ssh red-dragon /opt/gc/test-restore.sh            # verify newest dump against a throwaway db
```

### Monitoring

- **Container health** — `docker compose ps` on `/opt/gc/` shows `(healthy)` for both `db` (`pg_isready`) and `backend` (`/actuator/health`).
- **Logs** — `docker compose logs backend` / `db`. Compose's default log driver retains in-memory; for persistence, the host's journald captures the docker daemon output.
- **Restart policy** — `restart: unless-stopped` on both services covers the container-crash case. There is no separate watchdog cron; the legacy ADR-018 watchdog was AWS-specific and has not been ported. If health degrades without the container exiting, the next deploy's health gate or operator inspection will catch it.

### Initial host setup (one-time, historical)

The setup performed on red-dragon during cutover, captured for disaster-recovery reproduction:

```bash
# Directory layout
sudo mkdir -p /opt/gc /data/postgres /data/backups
sudo chown -R 999:999 /data/postgres
sudo chown atomik:atomik /opt/gc /data/backups

# Compose + env (env mirrors gc-dev's, but GC_IMAGE points at GHCR)
sudo cp deploy/docker/docker-compose.prod.yml /opt/gc/docker-compose.yml
sudo install -o atomik -g atomik -m 600 /dev/stdin /opt/gc/.env <<'EOF'
GC_DATABASE_URL=jdbc:postgresql://db:5432/ground_control
GC_DATABASE_USER=gc
GC_DATABASE_PASSWORD=...
GC_SERVER_PORT=8000
GC_CACHE_TYPE=none
JAVA_TOOL_OPTIONS=-Xmx512m -Xms256m
POSTGRES_DB=ground_control
POSTGRES_USER=gc
POSTGRES_PASSWORD=...
GC_IMAGE=ghcr.io/keplerops/ground-control:latest
GC_EMBEDDING_PROVIDER=openai
GC_EMBEDDING_API_KEY=...
EOF

# Deploy user (CI's SSH target, forced-command only)
sudo useradd -m -s /bin/bash gc-deploy
sudo usermod -aG docker gc-deploy
sudo install -d -o gc-deploy -g gc-deploy -m 700 /home/gc-deploy/.ssh
sudo install -o gc-deploy -g gc-deploy -m 600 /dev/stdin /home/gc-deploy/.ssh/authorized_keys <<EOF
command="/opt/gc/deploy.sh",restrict <ed25519-pubkey>
EOF

# Deploy script
sudo install -o root -g root -m 755 /dev/stdin /opt/gc/deploy.sh <<'SH'
#!/bin/bash
set -euo pipefail
cd /opt/gc
docker compose --env-file .env pull
docker compose --env-file .env up -d
for i in $(seq 1 30); do
  if curl -sf http://localhost:8000/actuator/health 2>/dev/null | grep -q '"UP"'; then
    echo "Deploy complete - application is UP"; exit 0
  fi; sleep 2
done
echo "ERROR: health check did not pass within 60s"
docker compose --env-file .env logs --tail=50 backend
exit 1
SH

# First start
gh auth token | docker login ghcr.io -u <github-user> --password-stdin
cd /opt/gc && docker compose --env-file .env up -d
```
