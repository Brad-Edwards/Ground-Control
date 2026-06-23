#!/bin/bash
# Deploy script for the red-dragon docker-compose stack (ADR-030).
#
# Invoked as the SSH forced command for the `gc-deploy` user — the CI deploy
# job SSHes to gc-deploy@red-dragon and the authorized_keys entry forces
# /opt/gc/deploy.sh to run with no argv. SSH exit code is this script's
# exit code, so the CI job's pass/fail reflects the deploy outcome.
#
# Contract:
#   - `/opt/gc/.env` pins `GC_IMAGE` to a FLOATING tag (`...:main` for
#     production). Each `docker compose pull` resolves that tag to whatever
#     the CI `docker` job most recently pushed, so the deploy script does
#     not need to be told the image SHA out-of-band.
#   - `/opt/gc/docker-compose.yml` is the production compose file (this repo
#     ships the canonical copy at `deploy/docker/docker-compose.prod.yml`).
#   - Health check runs INSIDE the backend container via `docker compose
#     exec` because the host port-binding is restricted to the tailnet IP
#     (per #828 / ADR-026 defense in depth); a host-side `curl localhost:8000`
#     can't reach the listener. The JRE Alpine base image ships `wget` but
#     not `curl`, so this script uses `wget`.
#   - Staleness guard (#953 / GC-P022): the deploy FAILS LOUDLY if the
#     freshly-pulled backend image carries no
#     `org.opencontainers.image.revision` label, or if that revision has not
#     advanced past the currently-running container's revision. A non-advancing
#     `:main` pull means `docker compose pull` resolved a frozen image — the
#     silent ~10-day staleness this guard exists to prevent. Set
#     `GC_ALLOW_SAME_REVISION=1` to permit an intentional same-image restart or
#     rollback (loud warning, never a silent bypass).
set -euo pipefail
cd /opt/gc

# Resolve the backend image ref from .env so we can inspect its OCI revision
# label after pulling (the compose file references it as ${GC_IMAGE}).
GC_IMAGE="$(grep -E '^GC_IMAGE=' .env | head -n1 | cut -d= -f2- || true)"

# Capture the revision the running backend container was built from BEFORE
# pulling, so a pull that does not advance the image is detectable.
running_rev=""
running_cid="$(docker compose --env-file .env ps -q backend 2>/dev/null || true)"
if [ -n "${running_cid}" ]; then
  running_rev="$(docker inspect \
    --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
    "${running_cid}" 2>/dev/null || true)"
fi

docker compose --env-file .env pull

# --- Staleness guard (#953 / GC-P022) --------------------------------------
pulled_rev=""
pulled_digest=""
if [ -n "${GC_IMAGE}" ]; then
  pulled_rev="$(docker image inspect \
    --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
    "${GC_IMAGE}" 2>/dev/null || true)"
  pulled_digest="$(docker image inspect \
    --format '{{ if .RepoDigests }}{{ index .RepoDigests 0 }}{{ end }}' \
    "${GC_IMAGE}" 2>/dev/null || true)"
fi

if [ -z "${pulled_rev}" ]; then
  echo "ERROR: pulled image '${GC_IMAGE:-<unresolved>}' has no" \
       "org.opencontainers.image.revision label."
  echo "       Cannot confirm the deploy is fresh; refusing to roll out."
  echo "       image ref/digest: ${GC_IMAGE:-<unresolved>} ${pulled_digest}"
  exit 1
fi

echo "Image revision: running='${running_rev:-<none>}' pulled='${pulled_rev}'"
echo "Image ref/digest: ${GC_IMAGE} ${pulled_digest}"

if [ -n "${running_rev}" ] && [ "${pulled_rev}" = "${running_rev}" ]; then
  if [ "${GC_ALLOW_SAME_REVISION:-}" = "1" ]; then
    echo "WARNING: pulled revision == running revision (${pulled_rev});" \
         "proceeding because GC_ALLOW_SAME_REVISION=1 (intentional restart/rollback)."
  else
    echo "ERROR: pulled image revision (${pulled_rev}) has not advanced past the"
    echo "       running container's revision. 'docker compose pull' resolved a"
    echo "       frozen image — the silent-stale-deploy failure mode (#953)."
    echo "       Verify GC_IMAGE's namespace/tag match CI's publish target."
    echo "       For an intentional same-image restart/rollback, re-run with"
    echo "       GC_ALLOW_SAME_REVISION=1."
    exit 1
  fi
fi
# ---------------------------------------------------------------------------

docker compose --env-file .env up -d
echo "Waiting for health check..."
for i in $(seq 1 30); do
  if docker compose --env-file .env exec -T backend \
       wget -q -O - http://localhost:8000/actuator/health 2>/dev/null \
       | grep -q '"UP"'; then
    echo "Deploy complete - application is UP"
    exit 0
  fi
  sleep 2
done
echo "ERROR: Health check did not pass within 60s"
docker compose --env-file .env logs --tail=50 backend
exit 1
