#!/bin/bash
# Ground Control DB backup (GC-P021).
#
# Runs as the `gc-backup` system user via `gc-backup.timer` (03:00, 11:00,
# 19:00 UTC, per the GC-P021 ≥ 3×/day floor). Three responsibilities:
#
#   1. Local logical dump — `pg_dump -Fc` inside the running `gc-db-1`
#      container → `/data/backups/gc-<UTC-timestamp>.dump`.
#   2. Off-box durability — rsync the dump to
#      `gc-backup@aurora:/var/backups/groundcontrol/` over the tailnet
#      via SSH (forced-command target on the aurora side: `rrsync
#      /var/backups/groundcontrol/`). Rsync failure is logged but does
#      not flip the script's exit code: the local dump already
#      succeeded, and the next timer fire retries. Repeated WARN lines
#      are the signal that aurora-side access has drifted and must be
#      reinvestigated.
#   3. Local retention — drop dumps older than GC_BACKUP_KEEP_DAYS
#      (default 30, which preserves ≥ 90 dumps at 3×/day).
#
# This is the canonical repo copy of `/opt/gc/backup.sh`. Drift policy
# is the same as `deploy/docker/deploy.sh`: edit here, PR through dev →
# main, copy the new file onto red-dragon. The repo copy is the source
# of truth.
set -euo pipefail

LOCAL_DIR=${GC_BACKUP_DIR:-/data/backups}
KEEP_DAYS=${GC_BACKUP_KEEP_DAYS:-30}
RSYNC_TARGET=${GC_BACKUP_RSYNC_TARGET:-gc-backup@aurora:/var/backups/groundcontrol/}
SSH_KEY=${GC_BACKUP_SSH_KEY:-/var/lib/gc-backup/.ssh/id_ed25519}
CONTAINER=${GC_BACKUP_DB_CONTAINER:-gc-db-1}

# `POSTGRES_USER` and `POSTGRES_DB` are not secrets — they are the
# database role and database name documented in DEPLOYMENT.md. The
# script intentionally does NOT read /opt/gc/.env (which is mode 600
# owned by gc-deploy and carries the actual password + ADR-026 tokens);
# `pg_dump` runs inside the container via `docker exec` and authenticates
# as the postgres uid in the container, so no password is needed here.
POSTGRES_USER=${POSTGRES_USER:-gc}
POSTGRES_DB=${POSTGRES_DB:-ground_control}

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
DUMP_FILE="${LOCAL_DIR}/gc-${TIMESTAMP}.dump"

# Sanity-check the database container before dumping. Fast-fail keeps a
# dead-container window from producing a zero-byte dump that overwrites
# nothing yet still counts toward retention rotation later.
if ! docker exec "${CONTAINER}" pg_isready -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" >/dev/null 2>&1; then
  echo "ERROR: ${CONTAINER} not ready for ${POSTGRES_USER}/${POSTGRES_DB}" >&2
  exit 1
fi

# Local dump. umask 077 keeps the dump readable only by gc-backup; the
# directory is mode 750 with the operator group.
umask 077
docker exec -i "${CONTAINER}" pg_dump -Fc -U "${POSTGRES_USER}" "${POSTGRES_DB}" > "${DUMP_FILE}"

if [ ! -s "${DUMP_FILE}" ]; then
  echo "ERROR: empty dump at ${DUMP_FILE}" >&2
  rm -f "${DUMP_FILE}"
  exit 1
fi

DUMP_SIZE=$(stat -c%s "${DUMP_FILE}")
echo "OK: local dump → ${DUMP_FILE} (${DUMP_SIZE} bytes)"

# Off-box: rsync to aurora via rrsync forced command. Best-effort.
# `./` on the target is relative to rrsync's locked-in directory
# (/var/backups/groundcontrol/ on aurora). ConnectTimeout + a short
# overall timeout keeps a hung tailnet path from blocking the timer
# slot indefinitely.
if timeout 600 rsync -a --partial \
    -e "ssh -i ${SSH_KEY} -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10" \
    "${DUMP_FILE}" "${RSYNC_TARGET}"; then
  echo "OK: off-box copy → ${RSYNC_TARGET}"
else
  echo "WARN: rsync to ${RSYNC_TARGET} failed; local dump retained" >&2
fi

# Local retention. find -delete prints each path; the wc -l counts the
# removed entries so the log line names the magnitude rather than just
# implying "some".
REMOVED=$(find "${LOCAL_DIR}" -maxdepth 1 -name 'gc-*.dump' -type f -mtime "+${KEEP_DAYS}" -print -delete | wc -l)
if [ "${REMOVED}" -gt 0 ]; then
  echo "OK: dropped ${REMOVED} local dump(s) older than ${KEEP_DAYS} days"
fi
