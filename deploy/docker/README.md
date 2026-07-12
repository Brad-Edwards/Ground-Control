# Production docker-compose deploy (red-dragon, ADR-030)

Deploys are operator-driven. Run `make deploy` (which calls
`scripts/deploy.sh`) from any tailnet host or from red-dragon itself; the
forced-command target on red-dragon is `/opt/gc/deploy.sh`. The `gc-deploy`
user's `authorized_keys` entry uses `command="/opt/gc/deploy.sh",restrict`,
so any SSH session as `gc-deploy` always runs `deploy.sh` regardless of
argv. There is no push-to-main CI deploy job; see the comment block in
`.github/workflows/ci.yml` above the removed `deploy:` job for context.
The `docker` job that builds and pushes the image to GHCR still runs on
every merge, so `:main` continues to track current `main`.

## Files in this directory

| File | What it is |
|---|---|
| `docker-compose.prod.yml` | Canonical production compose file. Mirror of `/opt/gc/docker-compose.yml` on red-dragon. |
| `deploy.sh` | Canonical on-host deploy script (forced-command target). Mirror of `/opt/gc/deploy.sh`. Validates env, drift-guards, rolls out with rollback, writes the deploy-state record. |
| `validate-env.sh` | Deploy-time validator: checks `/opt/gc/.env` against `env.schema` before rollout. Reports variable names only, never secret values. |
| `env.schema` | Single env contract (GC-P023). Read by both `validate-env.sh` and the `make policy` gate (`run_deploy_artifact_consistency`); neither carries its own copy of the rules. |
| `MANIFEST.sha256` | Canonical checksums of the four artifacts above. `deploy.sh` verifies the `/opt/gc` mirrors against it and refuses to roll out drifted files. Regenerate with `make deploy-manifest` after editing any artifact. |
| `.env.example` | Human template for `/opt/gc/.env`. The real `.env` carries secrets and is host-local; do not commit it. |

The operator wrapper that drives a deploy is `scripts/deploy.sh` at the repo root (invoked by `make deploy`); it syncs the artifacts above into `/opt/gc/` and publishes the deploy outcome to GitHub Deployments.

## Image resolution

`GC_IMAGE` in `/opt/gc/.env` MUST be an immutable versioned release tag like
`ghcr.io/autarchy-ai/ground-control:1.0.1` (ADR-063), never a floating branch
tag (`:main`, `:latest`, `:dev`). Promotion to production is the deliberate act
of bumping this pin to a cut release; a moving tag would let prod silently
re-promote whatever the CI `docker` job last pushed. `deploy.sh` runs `docker
compose pull` to resolve that versioned tag's digest, and the revision-advance
staleness guard (GC-P022) still confirms the pull actually moved. For a
rollback, pin the previous release's digest
(`ghcr.io/autarchy-ai/ground-control@sha256:...`) and set
`GC_ALLOW_IMAGE_PIN=1`; the deploy-time validator (`validate-env.sh`,
`RELEASE_PIN`) rejects a floating tag and rejects a bare digest without that
override.

## Health check

The backend's host port-binding is restricted to the tailnet IP only (per
#828 / ADR-026 defense in depth), so a host-side `curl localhost:8000`
can't reach the listener. The deploy script runs the health check INSIDE
the backend container via `docker compose exec` + `wget` (the JRE Alpine
base image ships `wget` but not `curl`). Inside the container the
listener is on all interfaces of its own network namespace, so `wget
http://localhost:8000` works regardless of the host bind.

## Env validation, rollback, and deploy state (GC-P023)

`deploy.sh` hardens every rollout:

- **Drift guard.** It checksum-verifies the `/opt/gc` mirrors against the
  synced `MANIFEST.sha256` and refuses to roll out drifted artifacts.
- **Env validation.** It runs `validate-env.sh` against `/opt/gc/.env` before
  restarting: required vars present, `GC_IMAGE` floating (a deliberate digest
  pin needs `GC_ALLOW_IMAGE_PIN=1`), ADR-026 credential slots all-or-nothing.
- **Rollback.** If the freshly pulled candidate fails its health window, the
  deploy restores the previous image and never leaves an unhealthy backend as
  the steady state.
- **Deploy state.** It writes `/opt/gc/deploy-state.json` (digest + commit SHA
  + outcome, no secrets) and emits a `DEPLOY_STATE_JSON` marker the wrapper
  publishes to GitHub Deployments; query it with `make deploy-status`.

## Drift policy

The committed artifacts (`deploy.sh`, `docker-compose.prod.yml`,
`validate-env.sh`, `env.schema`) are the single source of truth; the
`/opt/gc/*` files are runtime mirrors. Changes go through the repo:

1. Edit the repo copy on a feature branch; run `make deploy-manifest` if you
   touched a canonical artifact.
2. PR through dev → main per the normal workflow (`make policy` runs
   `run_deploy_artifact_consistency`, which fails on schema/compose drift, a
   stale manifest, a reintroduced second template/wrapper, or a wrapper that
   duplicates the rollout logic).
3. After merge, run `make deploy` from any tailnet host or red-dragon itself:
   the wrapper re-syncs `/opt/gc/` from the repo (no manual scp) and the
   on-host `deploy.sh` re-verifies the mirrors before rolling out. `/opt/gc/.env`
   is host-local and is never synced.
