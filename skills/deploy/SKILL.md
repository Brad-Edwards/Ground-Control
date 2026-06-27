---
name: deploy
description: Manually deploy Ground Control to red-dragon (the on-prem production host per ADR-030). Pulls the promoted image pinned by `GC_IMAGE`, restarts the stack via the canonical `/opt/gc/deploy.sh`, and verifies the actuator health check. Use for rollbacks, post-incident restart, or verifying an out-of-band push.
argument-hint:
disable-model-invocation: true
---

# Deploy (manual): red-dragon

The deploy path is **operator-driven manual deploy**: invoke `make deploy` (or `./scripts/deploy.sh`) from any tailnet host or from `red-dragon` itself, and the canonical `/opt/gc/deploy.sh` runs against the live stack. There is no push-to-main CI deploy job; fresh images land on `red-dragon` only when an operator runs the deploy.

Reasons to invoke `/deploy`:

- **Roll out a promoted release image.** `GC_IMAGE` in `/opt/gc/.env` points at the versioned release being promoted; this deploy pulls it.
- **Rollback** to a previously published image (pin `GC_IMAGE` in `/opt/gc/.env` and re-run).
- **Post-incident restart** when a container died or a deploy was missed.
- **Verifying an out-of-band push** (you just pushed a fix and want to confirm it landed without polling GHCR or `docker ps`).

## Architecture (one-paragraph reminder)

`gc-deploy@red-dragon` is an SSH login whose `authorized_keys` entry forces `command="/opt/gc/deploy.sh",restrict`. Any SSH session as `gc-deploy` runs that script and nothing else, so the SSH exit code reflects the deploy outcome (pass/fail). The script does `docker compose pull && up -d` against `/opt/gc/docker-compose.yml` with `/opt/gc/.env`, then polls `actuator/health` from inside the backend container (the host port-binding is restricted to the tailnet IP per #828 / ADR-026, so a host-side curl can't reach the listener). `GC_IMAGE` in `/opt/gc/.env` MUST be an immutable versioned release tag like `:1.0.1` (ADR-063), not a floating branch tag (`:main`, `:latest`, `:dev`) - production runs a promoted release and bumping this pin is the promotion act; `validate-env.sh` (`RELEASE_PIN`) rejects a floating tag, and rejects a bare digest unless `GC_ALLOW_IMAGE_PIN=1` confirms a deliberate rollback. The canonical mirrors of `/opt/gc/deploy.sh` and `/opt/gc/docker-compose.yml` live at `deploy/docker/deploy.sh` and `deploy/docker/docker-compose.prod.yml`. Drift policy (GC-P023): edit the repo copies and PR through dev → main; `make deploy` then re-syncs `/opt/gc/` from the checkout automatically (no manual scp), and `/opt/gc/deploy.sh` checksum-verifies the mirrors against `MANIFEST.sha256` and refuses to roll out drifted files. `/opt/gc/.env` is host-local secrets and is never synced.

## Step 1 - sanity-check current state

Before deploying, observe what's actually running. From any tailnet host:

```
ssh red-dragon 'docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"' | grep -E 'gc-(backend|db)'
```

Or on red-dragon itself: `docker ps --filter "name=gc"`.

Note the current image SHA / tag (the `IMAGE` column) and the uptime. If the running image is already what you wanted to deploy, you may not need to deploy at all - `docker compose pull` resolves the pinned `GC_IMAGE`, and if that is the same digest, the deploy is a no-op restart.

## Step 2 - run the deploy

```
make deploy
```

is equivalent to `./scripts/deploy.sh`, which is host-aware:

- **On red-dragon** (the box itself): runs `sudo -u gc-deploy /opt/gc/deploy.sh`.
- **From any other tailnet host**: SSHes `gc-deploy@red-dragon` (the forced-command path).

`make deploy` first syncs the canonical artifacts into `/opt/gc/` (no manual scp), then runs the on-host `deploy.sh`, which drift-guards the mirrors against `MANIFEST.sha256`, validates `/opt/gc/.env` against `env.schema`, pulls the image, enforces the #953 revision-advance guard, restarts containers, and polls `actuator/health` inside the backend container for up to 60 seconds. Expected output ends with `Deploy complete - application is UP`. If the candidate fails its health window, the deploy **automatically rolls back** to the previous image (output ends with "rolled back to previous image and service is UP") and exits non-zero — it never leaves a broken backend running. The rolled-out digest + commit SHA are written to `/opt/gc/deploy-state.json` and published to GitHub Deployments; query with `make deploy-status`.

## Step 3 - verify

Confirm the container is running the image you expected and the listener answers on the tailnet IP:

```
ssh red-dragon 'docker inspect gc-backend-1 --format "{{.Config.Image}} {{.State.Status}} {{.State.Health.Status}}"'
ssh red-dragon 'curl -sf http://100.98.28.66:8000/actuator/health'
```

(The literal `100.98.28.66` is red-dragon's tailnet IP; the host port-binding is restricted to that IP per `GC_BIND_IP` in `/opt/gc/.env`, so any tailnet client can hit it but the public interface stays dark.)

## Rollback

To roll back to a prior version, run a single command from any tailnet host with the repo checked out:

```
make rollback VERSION=1.0.1
```

or equivalently `./scripts/rollback.sh 1.0.1`. The script accepts:

- **Bare semver** (`1.0.1`) — derives the registry/image prefix from the current `GC_IMAGE` in `/opt/gc/.env`.
- **Full versioned ref** (`ghcr.io/autarchy-ai/ground-control:1.0.1`) — used as-is.
- **Digest ref** (`ghcr.io/autarchy-ai/ground-control@sha256:...`) — used as-is; automatically sets `GC_ALLOW_IMAGE_PIN=1` (deploy-time validation requires this explicit override for digest pins, per GC-P023).

Only immutable three-component release tags (`X.Y.Z`) or digests are accepted: two-component aliases (`1.0`), floating tags (`:main`, `:latest`), and untagged refs are rejected up front (ADR-063). A full or digest ref may only target the **same registry/repository** as the current pin — a rollback re-pins a version, it never repoints production at a different image source. Available versioned tags are listed at `https://github.com/autarchy-ai/Ground-Control/pkgs/container/ground-control`; `make deploy-status` and `/opt/gc/deploy-state.json` record the digest that actually served.

The wrapper patches `GC_IMAGE` (and `GC_ALLOW_IMAGE_PIN` for digest pins) in `/opt/gc/.env`, then delegates to `./scripts/deploy.sh` — the same validated deploy path as `make deploy`: drift guard, env validation, staleness guard, health gate, auto-rollback on failure, deploy-state publish, and GitHub Deployment record. If the rolled-back image itself fails its health window, the deploy auto-rolls back to whatever was serving before this run. Rollback is `deploy with a different pin`, not a separate code path.

Use `--dry-run` to preview what the script would pin without mutating anything:

```
./scripts/rollback.sh --dry-run 1.0.1
```

## When NOT to use this

- **Don't `docker compose up`, `restart`, or `recreate` containers ad-hoc on red-dragon.** Always go through `deploy.sh`. Ad-hoc compose commands skip the health check, leave the deploy ledger out of sync, and have caused outages before.
- **Don't deploy a branch or build-coordinate tag as the steady-state production pin.** Use a cut versioned release tag for promotion, or a deliberate digest pin for rollback/cutover.
