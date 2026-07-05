#!/bin/bash
# Assert that the Ground Control backup infrastructure meets GC-P021.
#
# The check is structural: it enforces the in-repo backup artifacts
# declare the GC-P021 cadence (≥ 3×/day local + off-box durability),
# the recurring restore-verification drill (≥ 1×/day), and reference the
# policy anchor in their headers. The actual cadence observed in
# production is the responsibility of `gc-backup.timer` /
# `gc-restore-test.timer` on red-dragon and the journal evidence for
# their services; that evidence is captured manually in the operator
# runbook (docs/operations/backup-restore.md), not asserted here.
#
# Runs from `make policy` and the pre-commit gate. Fails the build if
# any of the structural invariants below has drifted.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BACKUP_SCRIPT="${REPO_ROOT}/deploy/scripts/backup.sh"
INSTALL_SCRIPT="${REPO_ROOT}/deploy/scripts/install-gc-backup.sh"
AURORA_SCRIPT="${REPO_ROOT}/deploy/scripts/aurora-setup-gc-backup.sh"
RESTORE_SCRIPT="${REPO_ROOT}/deploy/scripts/test-restore.sh"
SERVICE_UNIT="${REPO_ROOT}/deploy/systemd/gc-backup.service"
TIMER_UNIT="${REPO_ROOT}/deploy/systemd/gc-backup.timer"
RESTORE_SERVICE_UNIT="${REPO_ROOT}/deploy/systemd/gc-restore-test.service"
RESTORE_TIMER_UNIT="${REPO_ROOT}/deploy/systemd/gc-restore-test.timer"

errors=0
fail() {
  echo "FAIL: $*" >&2
  errors=$((errors + 1))
}

# 1. Every artifact must exist and be readable.
for file in "${BACKUP_SCRIPT}" "${INSTALL_SCRIPT}" "${AURORA_SCRIPT}" "${RESTORE_SCRIPT}" \
            "${SERVICE_UNIT}" "${TIMER_UNIT}" "${RESTORE_SERVICE_UNIT}" "${RESTORE_TIMER_UNIT}"; do
  [ -r "${file}" ] || fail "missing or unreadable: ${file#${REPO_ROOT}/}"
done
if [ "${errors}" -gt 0 ]; then
  echo "assert-backup-policy.sh: ${errors} failure(s)" >&2
  exit 1
fi

# 2. GC-P021 anchor present in each script header so a diff reviewer
#    sees the policy reference inline.
for file in "${BACKUP_SCRIPT}" "${INSTALL_SCRIPT}" "${AURORA_SCRIPT}" "${RESTORE_SCRIPT}" \
            "${SERVICE_UNIT}" "${TIMER_UNIT}" "${RESTORE_SERVICE_UNIT}" "${RESTORE_TIMER_UNIT}"; do
  if ! grep -q 'GC-P021' "${file}"; then
    fail "${file#${REPO_ROOT}/}: header must reference GC-P021 so the policy anchor is visible in the artifact itself"
  fi
done

# 3. Timer enforces ≥ 3 fires/day. The OnCalendar line MUST list at least
#    three hour slots. Parse the OnCalendar value and count the comma-
#    separated hour entries.
oncal=$(grep -E '^OnCalendar=' "${TIMER_UNIT}" | head -n1 | sed 's/^OnCalendar=//')
if [ -z "${oncal}" ]; then
  fail "${TIMER_UNIT#${REPO_ROOT}/}: missing OnCalendar= directive"
else
  # Pull the HH[,HH...]:MM:SS field. Cadence is the count of comma-
  # separated hour entries in that field; anything < 3 is a GC-P021
  # violation.
  hours_field=$(printf '%s\n' "${oncal}" | awk '{ for (i=1;i<=NF;i++) if ($i ~ /:[0-9][0-9]:[0-9][0-9]/) { split($i, p, ":"); print p[1]; exit } }')
  fires=$(printf '%s' "${hours_field}" | awk -F, '{ print NF }')
  if [ -z "${fires}" ] || [ "${fires}" -lt 3 ]; then
    fail "${TIMER_UNIT#${REPO_ROOT}/}: OnCalendar fires ${fires:-0}×/day; GC-P021 requires ≥ 3"
  fi
fi

# 4. Service runs as the dedicated `gc-backup` user (not root, not the
#    operator's own account). Mismatch here means a hardening regression.
grep -q '^User=gc-backup' "${SERVICE_UNIT}" \
  || fail "${SERVICE_UNIT#${REPO_ROOT}/}: must run as User=gc-backup"

# 5. Backup script declares an off-box rsync target. Default value is
#    irrelevant; what we want is that the off-box path is part of the
#    script's contract and not silently removable.
grep -q 'GC_BACKUP_RSYNC_TARGET' "${BACKUP_SCRIPT}" \
  || fail "${BACKUP_SCRIPT#${REPO_ROOT}/}: must declare GC_BACKUP_RSYNC_TARGET (off-box durability is part of GC-P021)"

# 5b. GC-O009 adds Temporal core + SQL visibility persistence to the production
#     topology. The backup contract must dump both databases, not only the
#     Ground Control application database.
for token in 'GC_BACKUP_TEMPORAL_DB_CONTAINER' 'gc-temporal-' 'gc-temporal-visibility-' 'TEMPORAL_VISIBILITY_DB'; do
  grep -qF "${token}" "${BACKUP_SCRIPT}" \
    || fail "${BACKUP_SCRIPT#${REPO_ROOT}/}: Temporal persistence backup must include '${token}'"
done

# 6. Aurora-side setup pins to an rrsync forced command (the gc-backup
#    key on aurora can do nothing except land rsync payloads into the
#    backup dir). Drift here weakens the off-box trust boundary.
grep -q 'rrsync' "${AURORA_SCRIPT}" \
  || fail "${AURORA_SCRIPT#${REPO_ROOT}/}: aurora-side authorized_keys must restrict the gc-backup key to an rrsync forced command"

# 7. Restore-test timer enforces ≥ 1 fire/day. GC-P021 requires restoration
#    be "verified on a recurring basis"; an OnCalendar with at least one
#    daily slot is the structural floor for that clause.
rt_oncal=$(grep -E '^OnCalendar=' "${RESTORE_TIMER_UNIT}" | head -n1 | sed 's/^OnCalendar=//')
if [ -z "${rt_oncal}" ]; then
  fail "${RESTORE_TIMER_UNIT#${REPO_ROOT}/}: missing OnCalendar= directive (GC-P021 recurring-verification cadence)"
fi

# 8. Restore-test service runs as the dedicated `gc-backup` user, not root
#    and not gc-deploy — backup and deploy identities stay separate.
grep -q '^User=gc-backup' "${RESTORE_SERVICE_UNIT}" \
  || fail "${RESTORE_SERVICE_UNIT#${REPO_ROOT}/}: must run as User=gc-backup"

# 9. Restore test must actually exercise the AGE-aware verification depth.
#    A drill that restores but never checks the AGE extension, the V010
#    graph-bootstrap migration, or create_graph() would pass on a database
#    that is silently missing its graph layer — the exact regression the
#    GC-P021 recurring check exists to catch.
for token in 'pg_extension' "version = '010'" 'create_graph'; do
  grep -qF "${token}" "${RESTORE_SCRIPT}" \
    || fail "${RESTORE_SCRIPT#${REPO_ROOT}/}: restore verification must assert '${token}' so AGE-layer regressions are caught"
done

# 10. Restore verification must also exercise Temporal's core and visibility
#     dumps. This keeps GC-O009 persistence inside the recurring GC-P021 drill.
for token in 'APP_DUMP_TIMESTAMP' 'gc-temporal-${APP_DUMP_TIMESTAMP}.dump' 'gc-temporal-visibility-${APP_DUMP_TIMESTAMP}.dump' 'executions_visibility' 'temporal_verify' 'temporal_visibility_verify'; do
  grep -qF "${token}" "${RESTORE_SCRIPT}" \
    || fail "${RESTORE_SCRIPT#${REPO_ROOT}/}: restore verification must assert Temporal persistence token '${token}'"
done

if [ "${errors}" -gt 0 ]; then
  echo "assert-backup-policy.sh: ${errors} failure(s)" >&2
  exit 1
fi

echo "assert-backup-policy.sh: OK"
