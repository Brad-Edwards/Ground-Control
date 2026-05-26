#!/bin/bash
# Ground Control manual deploy wrapper.
#
# Routes the operator deploy to the canonical entry point at
# /opt/gc/deploy.sh on red-dragon (ADR-030):
#
#   - On red-dragon: runs `sudo -u gc-deploy /opt/gc/deploy.sh`.
#   - From any other tailnet host: SSHes `gc-deploy@red-dragon`. The
#     authorized_keys entry uses a forced command, so the SSH session
#     always runs /opt/gc/deploy.sh and reports its exit code as ours.
#
# The CI deploy job uses the same SSH forced-command path on every push
# to main. This wrapper is for operator-initiated deploys (rollback,
# post-incident restart, verifying an out-of-band push).
#
# Rollback / image pin: edit GC_IMAGE in /opt/gc/.env on red-dragon
# (mode 600, owned by gc-deploy) and re-run. `docker compose pull` then
# resolves the pinned ref on the next invocation. Argv-driven overrides
# are deliberately not supported — the SSH path is a forced command and
# would silently drop them.

set -euo pipefail

if [ "$#" -gt 0 ]; then
  echo "usage: $0   (no arguments)" >&2
  echo "for rollbacks: edit GC_IMAGE in /opt/gc/.env on red-dragon and re-run" >&2
  exit 2
fi

host="$(hostname -s 2>/dev/null || hostname)"
if [ "$host" = "red-dragon" ]; then
  exec sudo -u gc-deploy /opt/gc/deploy.sh
fi

exec ssh gc-deploy@red-dragon
