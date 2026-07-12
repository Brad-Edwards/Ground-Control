#!/bin/bash
# Ground Control restore test (GC-P021: restoration verified on a recurring basis).
#
# Runs as the `gc-backup` system user via `gc-restore-test.timer` (05:00
# UTC daily). Proves the most recent `pg_dump -Fc` produced by
# `deploy/scripts/backup.sh` is actually restorable, by restoring it into
# a throwaway `apache/age` container (never the live `gc-db-1`) and running
# the six GC-P021 operational-readiness sentinels below. Any failure exits
# non-zero so `gc-restore-test.service` fails and journald carries the
# paging signal; a clean run is the recurring evidence GC-P021 requires.
#
# Sentinels (all must pass — they are the authoritative gate, so a partial
# or truncated dump that pg_restore accepted with warnings still fails):
#   1. public schema contains at least one table
#   2. flyway_schema_history has recorded migrations
#   3. flyway_schema_history contains V010 (create_age_graph)
#   4. AGE extension is loaded in the restored database
#   5. core Ground Control tables are present (catches truncated dumps)
#   6. create_graph() succeeds against the restored catalog
#      (proves AGE is operationally usable, not just installed)
#
# Unlike the retired AWS-era copy, this script does NOT read /opt/gc/.env:
# the throwaway container is initialised fresh with a container-local
# random password, and only the non-secret POSTGRES_USER / POSTGRES_DB
# values (the database role and name, same defaults as backup.sh) need to
# match the dump. The hardened gc-backup identity therefore keeps zero
# access to the deploy-host secret file.
#
# This is the canonical repo copy of `/opt/gc/test-restore.sh`. Drift
# policy matches backup.sh: edit here, PR through dev → main, copy onto
# red-dragon. Overridable via environment for local dev:
#   GC_BACKUP_DIR            default /data/backups
#   GC_RESTORE_TEST_CONTAINER default gc-restore-test
#   GC_RESTORE_TEST_IMAGE     default apache/age:release_PG16_1.6.0
#   POSTGRES_USER / POSTGRES_DB  defaults gc / ground_control
set -euo pipefail

LOCAL_DIR=${GC_BACKUP_DIR:-/data/backups}
TEST_CONTAINER=${GC_RESTORE_TEST_CONTAINER:-gc-restore-test}
# Pinned to the same tag as the `db` service in
# deploy/docker/docker-compose.prod.yml. A prod image bump must bump this
# in lockstep (and invalidates ADR-025's AGE-drift mitigation — see the ADR).
DB_IMAGE=${GC_RESTORE_TEST_IMAGE:-apache/age:release_PG16_1.6.0}

# Non-secret database role + name (documented in DEPLOYMENT.md), same
# defaults backup.sh uses. The throwaway container is initialised with
# these so the dump's objects land in a matching role/database.
POSTGRES_USER=${POSTGRES_USER:-gc}
POSTGRES_DB=${POSTGRES_DB:-ground_control}

# Container-local throwaway password. Never logged; only the ephemeral
# container ever sees it, and the container is destroyed on exit.
PGPASSWORD_TEST="$(head -c 32 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 24)"

# Argument or latest local dump.
DUMP_FILE="${1:-$(ls -t "${LOCAL_DIR}"/gc-[0-9]*.dump 2>/dev/null | head -1)}"
[ -n "${DUMP_FILE}" ] || { echo "ERROR: no backup file found in ${LOCAL_DIR}" >&2; exit 1; }
[ -f "${DUMP_FILE}" ] || { echo "ERROR: file not found: ${DUMP_FILE}" >&2; exit 1; }
[ -s "${DUMP_FILE}" ] || { echo "ERROR: empty dump file: ${DUMP_FILE}" >&2; exit 1; }

DUMP_BASENAME="$(basename "${DUMP_FILE}")"
case "${DUMP_BASENAME}" in
  gc-[0-9]*.dump) ;;
  *) echo "ERROR: app dump filename must match gc-<UTC-timestamp>.dump: ${DUMP_FILE}" >&2; exit 1 ;;
esac

echo "Testing restore of: ${DUMP_FILE}"

cleanup() {
  docker rm -f "${TEST_CONTAINER}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Start clean in case a prior run was killed mid-flight.
docker rm -f "${TEST_CONTAINER}" >/dev/null 2>&1 || true

echo "Starting throwaway ${DB_IMAGE} container..."
docker run -d --name "${TEST_CONTAINER}" \
  -e POSTGRES_DB="${POSTGRES_DB}" \
  -e POSTGRES_USER="${POSTGRES_USER}" \
  -e POSTGRES_PASSWORD="${PGPASSWORD_TEST}" \
  "${DB_IMAGE}" >/dev/null

echo "Waiting for throwaway database..."
# Two-phase wait: pg_isready handles initial boot, then require three
# consecutive successful `SELECT 1` queries to confirm we are past the
# apache/age image's post-init restart window.
ready=0
for i in $(seq 1 120); do
  if docker exec "${TEST_CONTAINER}" pg_isready -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" >/dev/null 2>&1 \
     && docker exec "${TEST_CONTAINER}" \
          psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -q -t -A \
          -c "SELECT 1" >/dev/null 2>&1; then
    ready=$((ready + 1))
    [ "${ready}" -ge 3 ] && break
  else
    ready=0
  fi
  [ "$i" -eq 120 ] && { echo "FAIL: throwaway database did not become ready" >&2; exit 1; }
  sleep 1
done

echo "Restoring into throwaway container..."
# --no-owner / --no-acl avoid role mismatches; --clean --if-exists makes the
# restore idempotent. The restore must complete cleanly and its exit code is
# authoritative: unlike an in-place restore into the live gc-db-1 (whose
# ag_catalog is already populated and so emits ignorable duplicate-key
# diagnostics), the throwaway container starts with no AGE catalog, so a good
# dump restores with exit 0. A non-zero exit therefore means the restore is
# genuinely untrustworthy and the drill fails red — we deliberately do NOT
# downgrade it to a warning and lean on the sentinels alone, which would let a
# partial restore (objects or data the sentinels do not cover) still publish a
# clean GC-P021 verification. The sentinels below remain as defense in depth.
if ! docker exec -i "${TEST_CONTAINER}" \
    pg_restore -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    --clean --if-exists --no-owner --no-acl < "${DUMP_FILE}"; then
  echo "FAIL: pg_restore did not complete cleanly; restore is not trustworthy" >&2
  exit 1
fi
echo "OK: pg_restore completed cleanly"

# Runs queries as the restored-DB role; ON_ERROR_STOP makes a query error a
# non-zero exit so `set -e` aborts the script.
psql_scalar() {
  psql_scalar_in_db "${POSTGRES_DB}" "$1"
}

psql_scalar_in_db() {
  local db="$1" query="$2"
  docker exec "${TEST_CONTAINER}" \
    psql -U "${POSTGRES_USER}" -d "${db}" -t -A -v ON_ERROR_STOP=1 -c "${query}"
}

echo "Running verification queries..."

TABLE_COUNT="$(psql_scalar "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';")"
if [ "${TABLE_COUNT}" -gt 0 ]; then
  echo "PASS: ${TABLE_COUNT} public tables in restored database"
else
  echo "FAIL: no tables found after restore" >&2
  exit 1
fi

MIGRATION_COUNT="$(psql_scalar "SELECT count(*) FROM flyway_schema_history;" 2>/dev/null || echo "0")"
if [ "${MIGRATION_COUNT}" -gt 0 ]; then
  echo "PASS: ${MIGRATION_COUNT} Flyway migrations recorded"
else
  echo "FAIL: flyway_schema_history empty or missing" >&2
  exit 1
fi

# Flyway V010 marks the AGE graph bootstrap migration.
V010_PRESENT="$(psql_scalar "SELECT count(*) FROM flyway_schema_history WHERE version = '010';")"
if [ "${V010_PRESENT}" -ge 1 ]; then
  echo "PASS: V010 (create_age_graph) present in flyway_schema_history"
else
  echo "FAIL: V010 (create_age_graph) missing from flyway_schema_history" >&2
  exit 1
fi

# AGE extension loaded on the restored cluster.
AGE_EXT="$(psql_scalar "SELECT extname FROM pg_extension WHERE extname = 'age';")"
if [ "${AGE_EXT}" = "age" ]; then
  echo "PASS: AGE extension present"
else
  echo "FAIL: AGE extension not installed in restored database" >&2
  exit 1
fi

# Core Ground Control tables present (catches truncated dumps).
CORE_TABLES="project requirement requirement_relation traceability_link document section threat_model"
missing=""
for t in ${CORE_TABLES}; do
  exists="$(psql_scalar "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${t}';")"
  if [ "${exists}" -lt 1 ]; then
    missing="${missing} ${t}"
  fi
done
if [ -z "${missing}" ]; then
  echo "PASS: core Ground Control tables present (${CORE_TABLES})"
else
  echo "FAIL: core Ground Control tables missing:${missing}" >&2
  exit 1
fi

# Graph materialisable — proves AGE is loadable and create_graph() works
# against the restored catalog. Uses a throwaway graph name so a real
# `requirements` graph in the dump is never touched.
docker exec "${TEST_CONTAINER}" \
  psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -v ON_ERROR_STOP=1 -q -c \
  "LOAD 'age'; SET search_path = ag_catalog, \"\$user\", public; SELECT create_graph('requirements_verify'); SELECT drop_graph('requirements_verify', true);"
echo "PASS: graph materialisable via create_graph('requirements_verify')"

echo "$(date -u +%Y-%m-%dT%H:%M:%SZ): restore test PASSED — ${DUMP_FILE}"
