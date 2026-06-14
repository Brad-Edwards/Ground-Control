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
| `deploy.sh` | Canonical deploy script. Mirror of `/opt/gc/deploy.sh` on red-dragon. |
| `.env.example` | Template for `/opt/gc/.env`. The real `.env` carries secrets and is host-local; do not commit it. |

## Image resolution

`GC_IMAGE` in `/opt/gc/.env` MUST be a floating tag like
`ghcr.io/brad-edwards/ground-control:main`. `deploy.sh` runs `docker compose
pull` which resolves the tag to the current digest on GHCR, so each deploy
picks up whatever the CI `docker` job most recently pushed. Pinning
`GC_IMAGE` to a digest here freezes the deploy on that image forever - CI
builds will succeed but never roll out.

## Health check

The backend's host port-binding is restricted to the tailnet IP only (per
#828 / ADR-026 defense in depth), so a host-side `curl localhost:8000`
can't reach the listener. The deploy script runs the health check INSIDE
the backend container via `docker compose exec` + `wget` (the JRE Alpine
base image ships `wget` but not `curl`). Inside the container the
listener is on all interfaces of its own network namespace, so `wget
http://localhost:8000` works regardless of the host bind.

## Drift policy

The two committed files (`deploy.sh`, `docker-compose.prod.yml`) and their
red-dragon mirrors (`/opt/gc/deploy.sh`, `/opt/gc/docker-compose.yml`) are
the same artifact. Changes go through the repo:

1. Edit the repo copy on a feature branch.
2. PR through dev → main per the normal workflow.
3. After merge, SSH into red-dragon and copy the new file into `/opt/gc/`
   (the deploy SSH path uses the forced-command `deploy.sh` only - there's
   no general file-sync). Re-run with `sudo -u gc-deploy /opt/gc/deploy.sh`
   to confirm.

Improving this push step (and the deploy pipeline generally) is tracked on
the followup issue linked from `architecture/adrs/030-*.md`.
