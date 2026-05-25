#!/bin/bash
# Ground Control manual deploy helper.
#
# This is the repo copy of the script that lives on red-dragon at
# /opt/gc/deploy.sh and runs as the SSH forced-command target for the
# `gc-deploy` user (per ADR-030). The CI deploy job invokes it as
# `ssh gc-deploy@red-dragon` (no arguments). It is also a valid manual
# entry point for the operator from any tailnet host:
#
#   ssh gc-deploy@red-dragon              # CI / forced-command path (no args)
#   ssh red-dragon /opt/gc/deploy.sh      # operator path; same forced command
#   ./deploy/scripts/deploy.sh <image-ref> # local override; see below
#
# When invoked locally with an image-ref argument, this script rewrites
# /opt/gc/.env's GC_IMAGE line to that ref before pulling. The argument
# accepts any GHCR reference: a tag (`:latest`, `:dev`, `:sha-1a69bc3`)
# or a digest (`@sha256:...`). Production deployments pin to a digest in
# /opt/gc/.env directly; this override is for one-off rollbacks or
# operator-driven verification of a specific image.
#
# Note: the legacy AWS / SSM / refresh-env.sh path was removed when
# ADR-030 retired the AWS deployment. There is no SSM lookup; the
# canonical credential / config source is /opt/gc/.env (mode 600,
# operator-managed).
set -euo pipefail

GC_DIR=/opt/gc
cd "${GC_DIR}"

# Optional: rewrite GC_IMAGE to the supplied ref. The ref is taken
# verbatim, so callers that want a digest pin pass the full
# `ghcr.io/brad-edwards/ground-control@sha256:...` string; tag-style
# `ghcr.io/brad-edwards/ground-control:dev` also works.
if [ "${1:-}" != "" ]; then
  REF="$1"
  case "${REF}" in
    ghcr.io/*) ;;
    *) REF="ghcr.io/brad-edwards/ground-control:${REF}" ;;
  esac
  sed -i "s|^GC_IMAGE=.*|GC_IMAGE=${REF}|" .env
  echo "Pinned GC_IMAGE=${REF}"
fi

echo "Pulling images..."
docker compose --env-file .env pull

echo "Starting services..."
docker compose --env-file .env up -d

echo "Waiting for health check..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8000/actuator/health 2>/dev/null | grep -q '"UP"'; then
    echo "Deploy complete - application is UP"
    exit 0
  fi
  sleep 2
done

echo "ERROR: Health check did not pass within 60s"
docker compose --env-file .env logs --tail=50 backend
exit 1
