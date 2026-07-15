# Backup and Restore Runbook

This runbook returns Ground Control to an operational state after data loss or host failure. It assumes:

- You can reach the operator tailnet that owns `red-dragon` and `aurora`.
- You have SSH into `red-dragon` as a sudoer (typically `atomik`).
- You have **no prior exposure** to Ground Control internals. Everything you need is either here or linked inline.

Anchors:

- Policy: Ground Control requirement GC-P021. This runbook satisfies its "documented such that recovery can be performed without prior knowledge" clause.
- Architecture context: [ADR-025](../../architecture/adrs/025-backup-policy.md), [ADR-030](../../architecture/adrs/030-on-prem-hetzner-deployment.md).

## 1. What is backed up

Ground Control runs on a single docker-compose stack on `red-dragon` (per ADR-030). One backup layer protects the relational data:

| Layer | What | Where | Cadence | Retention |
|-------|------|-------|---------|-----------|
| Local logical dump | `pg_dump -Fc` of the `ground_control` database, written from inside the `gc-db-1` container | `/data/backups/gc-<UTC-timestamp>.dump` on red-dragon | 03:00, 11:00, 19:00 UTC (`gc-backup.timer`) | 30 days (`GC_BACKUP_KEEP_DAYS`) |
| Off-box copy | Same dump, rsync'd over the tailnet | `gc-backup@aurora:/var/backups/groundcontrol/` (via `rrsync` forced command) | After each local dump | per-aurora retention |

The relational tables are the authoritative source of truth. The Apache AGE graph stored in `ag_catalog` is derivative; it is rebuilt from the relational tables by `POST /api/v1/admin/graph/materialize` (code: `AgeGraphService.materializeGraph()`).

The off-box copy is best-effort within a given timer slot: if aurora is unreachable the local dump still succeeds and the script logs `WARN`. Repeated `WARN` lines across multiple slots are the signal that aurora-side access has drifted and must be reinvestigated; without the off-box copy the GC-P021 durability clause is not satisfied.

A second timer, `gc-restore-test.timer`, proves the dump is actually restorable. Daily at 05:00 UTC it runs `/opt/gc/test-restore.sh`, which selects the newest app dump and restores it into a throwaway database (never the live `gc-db-1`). It asserts six operational-readiness sentinels: the public schema has tables, `flyway_schema_history` has recorded migrations including V010 (create_age_graph), the AGE extension is loaded, the core Ground Control tables are present, and `create_graph()` succeeds against the restored catalog. The sentinels are the gate, so a partial or truncated dump fails even if `pg_restore` accepted it with warnings. Any failure exits the service non-zero; that journal entry is the paging signal. This is the GC-P021 "verified on a recurring basis" clause. The test needs no secret (the throwaway container uses a container-local password), so it runs under the same `gc-backup` identity as the backup itself.

Secrets live in `/opt/gc/.env` on red-dragon (mode 600, owned by `gc-deploy`). They are not in the backups; treat them as out-of-band operator-managed material and store a copy alongside the operator's other host secrets.

## 2. Locating resources

```bash
# Backup + restore-test timer state and next fire.
ssh red-dragon 'systemctl list-timers gc-backup.timer gc-restore-test.timer'

# Most recent restore-test run (the recurring GC-P021 verification).
ssh red-dragon 'sudo journalctl -u gc-restore-test.service --since "2 days ago" --no-pager | tail -40'

# The most recent dumps on red-dragon.
ssh red-dragon 'sudo ls -lht /data/backups/ | head'

# The most recent off-box copies on aurora.
ssh aurora 'sudo ls -lht /var/backups/groundcontrol/ | head'

# Backup-run history.
ssh red-dragon 'sudo journalctl -u gc-backup.service --since "7 days ago" --no-pager | tail -100'
```

## 3. Manual operations

### Take an ad-hoc dump

```bash
ssh red-dragon 'sudo systemctl start gc-backup.service'
ssh red-dragon 'sudo journalctl -u gc-backup.service --since "5 minutes ago" --no-pager | tail'
```

The unit is `Type=oneshot`; `systemctl start` runs it once and returns when the dump completes. Exit code 0 means the local dump succeeded; the journal line `WARN: rsync to ... failed` means the off-box copy did not.

### List dumps

```bash
ssh red-dragon 'sudo ls -lht /data/backups/'
```

Dumps are named `gc-<YYYYMMDDThhmmssZ>.dump`. The timestamp is UTC.

### Restore in place (data corruption, instance still running)

Use this when the database is up but the data is wrong (bad migration, user error, accidental delete).

```bash
# 1. Pick a dump from before the corruption.
ssh red-dragon 'sudo ls -lht /data/backups/ | head -10'

# 2. Copy the chosen dump into the db container.
ssh red-dragon 'sudo docker cp /data/backups/gc-<TIMESTAMP>.dump gc-db-1:/tmp/restore.dump'

# 3. Restore. --clean --if-exists drops existing objects first so the
#    restore overwrites cleanly; --no-owner / --no-acl avoids role
#    mismatches on the destination.
ssh red-dragon 'sudo docker exec -i gc-db-1 pg_restore -U gc -d ground_control \
  --clean --if-exists --no-owner --no-acl -j 4 /tmp/restore.dump'
```

Apache AGE extension state (`ag_graph`, `ag_label`) emits ignorable duplicate-key errors during restore; those tables are pre-populated at db init. Application data restores cleanly. Continue with [§ 5](#5-rematerialize-the-age-graph) and [§ 6](#6-post-restore-verification).

### Restore from the off-box copy

Use this when red-dragon's local `/data/backups/` is unavailable (disk lost, host lost, partial corruption that took the dumps with it).

```bash
# 1. List off-box dumps.
ssh aurora 'sudo ls -lht /var/backups/groundcontrol/ | head'

# 2. Pull the chosen dump back to red-dragon (or to a replacement host).
ssh aurora 'sudo cat /var/backups/groundcontrol/gc-<TIMESTAMP>.dump' \
  > /tmp/restore.dump

# 3. Continue with the in-place restore steps above.
```

## 4. Full host loss (rebuild)

Use this when red-dragon is gone and the operator is bringing the workload up on a replacement host.

1. Provision the replacement host per [Initial deploy-host setup](../deployment/DEPLOYMENT.md#initial-deploy-host-setup-required) in DEPLOYMENT.md. This creates the `gc-deploy` user, `/opt/gc/`, the compose stack, and the firewall unit.
2. Install the backup mechanism: `sudo bash deploy/scripts/install-gc-backup.sh`. Capture the printed pubkey.
3. On aurora, install the new pubkey: `sudo bash deploy/scripts/aurora-setup-gc-backup.sh '<pubkey>'`. (The previous red-dragon's pubkey entry stays in aurora's `authorized_keys`; clean it up if you want, or leave it as harmless dead key material.)
4. Pull the newest off-box dump to the replacement host. Restore via [§ 3 in-place restore](#restore-in-place-data-corruption-instance-still-running) (skip the "copy into container" step; copy from `aurora:/var/backups/groundcontrol/` instead).
5. Rematerialize the AGE graph (see [§ 5](#5-rematerialize-the-age-graph)).
6. Verify (see [§ 6](#6-post-restore-verification)).

## 5. Rematerialize the AGE graph

After **any** restore from a pg_dump, trigger the graph rematerialize. This ensures the AGE graph reflects the restored relational data even if the dump's `ag_catalog` OIDs drifted from what the fresh `create_graph('requirements')` allocated during the V010 Flyway migration.

```bash
# From the red-dragon host (reaches localhost:8000 via the tailnet bind):
ssh red-dragon 'curl -sf -X POST http://100.98.28.66:8000/api/v1/admin/graph/materialize \
  -H "Authorization: Bearer $GROUND_CONTROL_API_TOKEN"'

# From a workstation with Tailscale + MagicDNS:
curl -sf -X POST http://red-dragon:8000/api/v1/admin/graph/materialize \
  -H "Authorization: Bearer $GROUND_CONTROL_API_TOKEN"
```

The endpoint returns HTTP 200 with no body. Confirm the graph is populated:

```bash
curl -sf "http://red-dragon:8000/api/v1/analysis/dashboard-stats?project=ground-control" \
  -H "Authorization: Bearer $GROUND_CONTROL_API_TOKEN" | jq .
```

Non-zero counts for requirements and links mean the restored state is operational.

## 6. Post-restore verification

Run **all** of the following from a workstation in the tailnet:

```bash
# 6a. Database is responding.
ssh red-dragon 'sudo docker exec gc-db-1 pg_isready -U gc -d ground_control'

# 6b. Spring Boot health.
curl -sf http://red-dragon:8000/actuator/health | jq .status
# Expect: "UP"

# 6c. Data reachable via the API.
curl -sf "http://red-dragon:8000/api/v1/analysis/dashboard-stats?project=ground-control" \
  -H "Authorization: Bearer $GROUND_CONTROL_API_TOKEN" | jq .
# Expect non-zero requirement / link counts that match expectations for the restored point in time.

# 6d. Next scheduled backup succeeds.
ssh red-dragon 'sudo systemctl start gc-backup.service && \
  sudo journalctl -u gc-backup.service --since "5 minutes ago" --no-pager | tail'
# Expect "OK: local dump" and "OK: off-box copy" lines.
```

If any check fails, return to [§ 3](#3-manual-operations) and pick a different source of truth (an earlier dump).

## 7. Rotating credentials

The database password lives in `/opt/gc/.env` (mode 600, `gc-deploy`). The backup mechanism does **not** read the password; `pg_dump` runs inside the container and uses the container's peer authentication. Credential rotation therefore affects the application but not the backup path. Rotate:

```bash
# 1. Update the password in the db container and in /opt/gc/.env.
ssh red-dragon
sudo -u gc-deploy nano /opt/gc/.env   # edit POSTGRES_PASSWORD and GC_DATABASE_PASSWORD
sudo docker exec gc-db-1 psql -U postgres -d ground_control \
  -c "ALTER USER gc WITH PASSWORD '<new-password>';"

# 2. Restart the backend so it picks up the new password.
sudo -u gc-deploy /opt/gc/deploy.sh

# 3. Force a backup to confirm the path still works.
sudo systemctl start gc-backup.service
```

The dump itself does **not** carry the password; restoring from a dump does not change role passwords. After a restore the existing `gc` role's password remains whatever it was on the destination host.

## 8. Escalation

- Repository: <https://github.com/autarchy-ai/Ground-Control>
- File issues with the `ops` label if this runbook is wrong or incomplete.

## Appendix A. Where the tooling lives

| Path (on red-dragon) | Purpose |
|------|---------|
| `/opt/gc/backup.sh` | The dump-and-rsync script. Canonical copy: `deploy/scripts/backup.sh`. |
| `/opt/gc/test-restore.sh` | The restore-verification drill (throwaway-container restore + sentinels). Canonical copy: `deploy/scripts/test-restore.sh`. |
| `/etc/systemd/system/gc-backup.service` | Oneshot unit that runs `/opt/gc/backup.sh` as the `gc-backup` user. Canonical copy: `deploy/systemd/gc-backup.service`. |
| `/etc/systemd/system/gc-backup.timer` | Schedules the service at 03:00, 11:00, 19:00 UTC. Canonical copy: `deploy/systemd/gc-backup.timer`. |
| `/etc/systemd/system/gc-restore-test.service` | Oneshot unit that runs `/opt/gc/test-restore.sh` as the `gc-backup` user. Canonical copy: `deploy/systemd/gc-restore-test.service`. |
| `/etc/systemd/system/gc-restore-test.timer` | Schedules the restore test daily at 05:00 UTC. Canonical copy: `deploy/systemd/gc-restore-test.timer`. |
| `/var/lib/gc-backup/.ssh/id_ed25519` | SSH key the script uses to rsync to aurora. Mode 600, owned by `gc-backup`. Generated by `deploy/scripts/install-gc-backup.sh`. |
| `/data/backups/` | Dump landing zone, owned by `gc-backup:atomik` (setgid). |
| journal under `gc-backup.service` | Backup-run log. Pull with `sudo journalctl -u gc-backup.service`. |

Aurora-side:

| Path (on aurora) | Purpose |
|------|---------|
| `/var/lib/gc-backup/.ssh/authorized_keys` | Carries the red-dragon pubkey behind a `command="rrsync /var/backups/groundcontrol/",restrict` forced command. The key can do nothing else. |
| `/var/backups/groundcontrol/` | Off-box dump landing zone, owned by `gc-backup`. |

Installer scripts:

| Path (in repo) | Purpose |
|------|---------|
| `deploy/scripts/install-gc-backup.sh` | Idempotent red-dragon-side installer (user + key + files + timer). |
| `deploy/scripts/aurora-setup-gc-backup.sh` | Idempotent aurora-side installer (user + dir + authorized_keys with rrsync forced command). Takes the red-dragon pubkey as `$1`. |
