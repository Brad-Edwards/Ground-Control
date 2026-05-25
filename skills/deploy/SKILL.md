---
name: deploy
description: Manually deploy Ground Control to red-dragon (the on-prem production host per ADR-030). Pulls the latest `:main` image from GHCR, restarts the stack via the canonical `/opt/gc/deploy.sh`, and verifies the actuator health check. Use for rollbacks, post-incident restart, or verifying an out-of-band push.
argument-hint:
disable-model-invocation: true
---

# Deploy (manual): red-dragon

The deploy path is **operator-driven manual deploy**: invoke `make deploy` (or `./scripts/deploy.sh`) from any tailnet host or from `red-dragon` itself, and the canonical `/opt/gc/deploy.sh` runs against the live stack. There is no push-to-main CI deploy job; fresh images land on `red-dragon` only when an operator runs the deploy.

Reasons to invoke `/deploy`:

- **Roll out a fresh `:main` image.** A new build has been pushed to GHCR (the `docker` job runs on every push to `main` and is still active); this deploy pulls it.
- **Rollback** to a previously published image (pin `GC_IMAGE` in `/opt/gc/.env` and re-run).
- **Post-incident restart** when a container died or a deploy was missed.
- **Verifying an out-of-band push** (you just pushed a fix and want to confirm it landed without polling GHCR or `docker ps`).

## Architecture (one-paragraph reminder)

`gc-deploy@red-dragon` is an SSH login whose `authorized_keys` entry forces `command="/opt/gc/deploy.sh",restrict`. Any SSH session as `gc-deploy` runs that script and nothing else, so the SSH exit code reflects the deploy outcome (pass/fail). The script does `docker compose pull && up -d` against `/opt/gc/docker-compose.yml` with `/opt/gc/.env`, then polls `actuator/health` from inside the backend container (the host port-binding is restricted to the tailnet IP per #828 / ADR-026, so a host-side curl can't reach the listener). `GC_IMAGE` in `/opt/gc/.env` MUST be a floating tag like `:main`—pinning a digest there freezes the deploy on that image forever. The canonical mirrors of `/opt/gc/deploy.sh` and `/opt/gc/docker-compose.yml` live at `deploy/docker/deploy.sh` and `deploy/docker/docker-compose.prod.yml`. Drift policy: edit the repo copies, PR through dev → main, then SSH into red-dragon and copy the new files into `/opt/gc/`.

## Step 1—sanity-check current state

Before deploying, observe what's actually running. From any tailnet host:

```
ssh red-dragon 'docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"' | grep -E 'gc-(backend|db)'
```

Or on red-dragon itself: `docker ps --filter "name=gc"`.

Note the current image SHA / tag (the `IMAGE` column) and the uptime. If the running image is already what you wanted to deploy, you may not need to deploy at all—`docker compose pull` will resolve `:main` to whatever was most recently pushed, but if that's the same digest, the deploy is a no-op restart.

## Step 2—run the deploy

```
make deploy
```

is equivalent to `./scripts/deploy.sh`, which is host-aware:

- **On red-dragon** (the box itself): runs `sudo -u gc-deploy /opt/gc/deploy.sh`.
- **From any other tailnet host**: SSHes `gc-deploy@red-dragon` (the forced-command path).

Expected output ends with `Deploy complete - application is UP`. The script pulls the image, restarts containers, then polls `actuator/health` inside the backend container for up to 60 seconds. Non-zero exit means the health check failed—the script tails the last 50 lines of backend logs before exiting.

## Step 3—verify

Confirm the container is running the image you expected and the listener answers on the tailnet IP:

```
ssh red-dragon 'docker inspect gc-backend-1 --format "{{.Config.Image}} {{.State.Status}} {{.State.Health.Status}}"'
ssh red-dragon 'curl -sf http://100.98.28.66:8000/actuator/health'
```

(The literal `100.98.28.66` is red-dragon's tailnet IP; the host port-binding is restricted to that IP per `GC_BIND_IP` in `/opt/gc/.env`, so any tailnet client can hit it but the public interface stays dark.)

## Rollback

There is no argv-driven rollback over the SSH forced-command path (it ignores client argv by design). To roll back:

1. SSH into red-dragon as a sudoer (for example, `ssh red-dragon`).
2. `sudo -u gc-deploy vi /opt/gc/.env` and pin `GC_IMAGE` to the target ref—either a tag (`ghcr.io/brad-edwards/ground-control:sha-abc123`) or a digest (`ghcr.io/brad-edwards/ground-control@sha256:...`). Available tags are listed at `https://github.com/Brad-Edwards/Ground-Control/pkgs/container/ground-control`; CI publishes `sha-<short>` tags for every `main` build.
3. Re-run `make deploy` (or `sudo -u gc-deploy /opt/gc/deploy.sh`). The pull resolves the pinned ref and the restart picks it up.
4. **Restore the floating `:main` pin** in `/opt/gc/.env` once the rollback is no longer needed—otherwise the next CI deploy will succeed but never actually roll out.

## When NOT to use this

- **Don't `docker compose up`, `restart`, or `recreate` containers ad-hoc on red-dragon.** Always go through `deploy.sh`. Ad-hoc compose commands skip the health check, leave the deploy ledger out of sync, and have caused outages before.
- **Don't deploy a branch that hasn't been merged to `main`.** The image tag `:main` only updates from `main` builds; deploying anything else means hand-pinning a `sha-` tag in `.env`, which counts as a rollback (see above) and must be unpinned afterward.
