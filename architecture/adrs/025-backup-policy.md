# ADR-025: Backup Policy (GC-P021)

## Status

Accepted (2026-04-19); amended 2026-06-24 for the on-prem red-dragon
deployment. See [Amendment 2026-06-24](#amendment-2026-06-24-rsync-to-aurora-on-red-dragon-978). The original
Context/Decision below records the AWS EC2 mechanism (S3 + EBS DLM + SSM +
Terraform cron) and is retained as history; ADR-018 and that mechanism are
superseded by [ADR-030](030-on-prem-hetzner-deployment.md). The GC-P021
*policy* (≥ 3×/day, off-box durability, recurring verified restore,
cold-recovery documentation) is unchanged; only the *mechanism* changed.

## Date

2026-04-19

## Context

GC-P021 is a non-functional policy that the production Ground Control
deployment must meet:

> The Ground Control production deployment shall have its persistent data
> backed up at least three times per day, with at least twenty-four (24)
> hours of backup retention. Backups shall cover all persistent state
> required to restore the system to an operational condition. Restoration
> from backup shall be verified on a recurring basis and documented such
> that recovery can be performed without prior knowledge of the live
> system.

ADR-018 established the AWS EC2 deployment with two independent backup
layers:

1. **Logical** - `pg_dump -Fc` via cron on the EC2 host → local dump on the
   `/data` volume + S3 (`groundcontrol-backups-catalyst-dev`, versioned,
   SSE-S3, public-access-blocked, 30-day lifecycle).
2. **Block-level** - AWS Data Lifecycle Manager daily EBS snapshots of the
   `/data` volume, 7-day retention.

Restore tooling:

- `/opt/gc/restore.sh` - restore-in-place from a local dump or S3 key,
  with a pre-restore safety backup.
- `/opt/gc/test-restore.sh` - throwaway-container restore of the latest
  dump, invoked weekly (Sunday 05:00 UTC) via cron.

The defaults under ADR-018 were:

- Backup cadence: `0 3 * * *` (once daily at 03:00 UTC).
- Local retention: 3 dump files.
- Verification cadence: weekly.
- Verification checks: count of public-schema tables and Flyway migration
  rows only.

These satisfy GC-P009 ("configurable automated backups with defined
retention and tested restore") but do not satisfy GC-P021:

- Cadence is 1×/day, not 3×/day.
- Verification is weekly and does not exercise Apache AGE - the restored
  database could be silently missing the AGE extension or its catalog and
  the existing checks would still pass.
- Recovery documentation is embedded in `docs/deployment/DEPLOYMENT.md`
  and mixes local-dev with prod instructions; it does not meet the
  "recovery without prior knowledge" bar.

Issue #533 proposed replacing the logical backup path with **pgBackRest**
(block-level Postgres backup with WAL archiving). Two reasons make that
path the wrong choice for this deployment:

1. **Apache AGE data is derivative.** The authoritative state for
   requirements, relations, documents, sections, threat models, risk, and
   traceability lives in relational tables governed by Envers auditing.
   `AgeGraphService.materializeGraph()` rebuilds the AGE graph from those
   tables (see
   `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/age/AgeGraphService.java`).
   Preserving the AGE graph at byte-for-byte fidelity is not required - a
   post-restore `materializeGraph` call re-derives it from restored
   relational data.
2. **Operational cost outweighs the benefit.** The deployment is a single
   `t3a.small` (2 vCPU / 2 GB RAM). Enabling pgBackRest's archive-command
   and WAL shipping adds a continuous Postgres workload and new
   sidecar/host process for a single-tenant dev tool. GC-P021 does not ask
   for PITR finer than the backup cadence, so the additional machinery
   buys nothing we need.

## Decision

> **Superseded by the [2026-06-24 amendment](#amendment-2026-06-24-rsync-to-aurora-on-red-dragon-978).**
> The decision below is the AWS EC2 mechanism. On red-dragon there is no
> S3 bucket, no EBS DLM layer, no SSM rollout, and no Terraform/cron; the
> `restore.sh`, `test-restore.sh`, and `install-ops-scripts.sh` named here
> were removed. Read the amendment for the current mechanism.

Retain the ADR-018 two-layer backup architecture (pg_dump + EBS) and
raise the defaults so GC-P021 is met:

1. **Cadence** - `backup_cron` default changes from `0 3 * * *` to
   `0 3,11,19 * * *` (8-hour interval, 3×/day). Override is still allowed
   but the variable description records that any override must remain
   ≥ 3×/day to stay compliant.
2. **Retention** - `local_retention_count` default raises from 3 to 4
   dump files, guaranteeing > 24 h retention at 3×/day cadence with a
   one-run margin. S3 lifecycle stays at 30 days (cost-governed, already
   ≥ 24 h).
3. **Verification cadence** - the restore-test cron changes from weekly
   (`0 5 * * 0`) to daily (`0 5 * * *`).
4. **Verification depth** - `deploy/scripts/test-restore.sh` and the
   inlined copy in `deploy/terraform/modules/compute/user-data.sh.tftpl`
   now additionally assert:
   - AGE extension is present (`pg_extension` row).
   - Core Ground Control tables are present (`project`, `requirement`,
     `requirement_relation`, `traceability_link`, `document`, `section`,
     `threat_model`).
   - V010 (`create_age_graph`) appears in `flyway_schema_history`.
   - `create_graph('requirements_verify')` succeeds against the restored
     catalog - this proves AGE is operationally usable, not just installed.
5. **Documentation** - a new standalone runbook lives at
   `docs/operations/backup-restore.md`. It is written for an operator with
   AWS console access, Tailscale credentials, and no prior exposure to
   Ground Control internals; it is executable end-to-end without
   consulting other documents.
6. **Script rollout** - because `deploy/terraform/modules/compute/main.tf`
   declares `ignore_changes = [ami, user_data]`, Terraform cannot rewrite
   `/opt/gc/*.sh` on the live instance on updates. The CI `deploy` job
   closes that gap: after `terraform apply` and before running
   `/opt/gc/deploy.sh`, it `aws ssm send-command`s the contents of
   `deploy/scripts/install-ops-scripts.sh` to the running instance with
   the GC-P021 env vars (`GC_BACKUP_BUCKET`, `GC_BACKUP_CRON`,
   `GC_LOCAL_RETENTION_COUNT`). The installer is idempotent, enforces
   the ≥ 3×/day cadence and ≥ 4-file retention floors on input, and
   rewrites `/opt/gc/{backup,restore,test-restore,watchdog}.sh` plus the
   three `/etc/cron.d/gc-*` entries on every deploy. First-boot
   provisioning uses the same installer, keeping one source of truth.
7. **Drift protection** - `scripts/assert-backup-policy.sh` is a
   structural check over the Terraform module and env vars, the inlined
   user-data copy, the canonical `deploy/scripts/test-restore.sh`, and
   the installer. It also executes the installer against an ephemeral
   prefix to prove (a) it refuses non-compliant inputs and (b) it
   writes the expected files and substitutions. It runs in pre-commit
   and `make policy` and fails the build if cadence, retention, cron,
   verification, or CI-workflow wiring drifts away from the GC-P021
   defaults. `scripts/test-backup-restore-locally.sh` is a second
   guardrail: it stands up a fresh apache/age container, replays every
   Flyway migration, dumps, and exercises `test-restore.sh` against the
   dump end-to-end, asserting every sentinel check.

## Consequences

### Positive

- Zero architecture churn - reuses the existing backup/restore tooling.
- Verification now catches AGE-specific regressions that the previous
  table-count check would have missed silently.
- Daily verification shrinks the blind window between "backup taken" and
  "backup proven restorable" from up to 7 days to at most 24 h.
- The standalone runbook lets any operator execute recovery cold.
- The structural assertions prevent accidental regression of the
  GC-P021-critical defaults in future Terraform or script changes.

### Negative

- Not true PITR - effective RPO is the cadence interval (~8 h). GC-P021
  does not require finer PITR, but teams that grow tighter RPO needs must
  revisit this ADR.
- 3× more S3 PUTs and roughly 3× S3 storage for local + S3 copies.
  At ~500 MB per dump × 30-day retention this is still pennies per month,
  but the ADR records the trade-off for future cost review.
- pg_dump runs against the live container; at current data volume
  (low-single-digit GB) it completes in seconds, but a future order-of-
  magnitude growth may require moving to online physical backup.

### Risks

- **AGE extension drift** - if a future Postgres or AGE image upgrade
  changes the catalog layout, a dump taken pre-upgrade may fail to
  restore post-upgrade. Mitigation: verification runs against the same
  `apache/age:release_PG16_1.6.0` image tag pinned in
  `docker-compose.yml`, `deploy/docker/docker-compose.prod.yml`, and the
  `user-data.sh.tftpl` inline `test-restore.sh`. A version upgrade touches
  all three and invalidates this ADR; a new ADR is required at that time.
- **Verification container cold-start flakiness** - the apache/age image
  briefly restarts after its first boot. `test-restore.sh` now waits for
  three consecutive successful `SELECT 1` calls before restoring. If the
  restart window ever exceeds the 120-second wait, verification fails
  loudly rather than silently - acceptable behaviour.
- **Backup cron failures that silently fall under retention** - at 4-file
  local retention and 3 runs/day, two consecutive failed backups still
  leave ≥ 24 h of valid dumps. Three consecutive failures would drop
  retention below the policy; operators rely on
  `/var/log/gc-backup.log` + the daily restore-test failure to catch
  this.

## Amendment 2026-06-24: rsync-to-aurora on red-dragon (#978)

### Context

[ADR-030](030-on-prem-hetzner-deployment.md) moved the production
deployment off AWS EC2 onto the on-prem Hetzner host `red-dragon`, a
single docker-compose stack on the operator tailnet. That deletes every
mechanism the original Decision relied on: the S3 backup bucket, the EBS
DLM block-level snapshot layer, the SSM `send-command` rollout, the
Terraform-managed instance, and the cron schedule. The AWS-era operator
scripts (`restore.sh`, `test-restore.sh`, `install-ops-scripts.sh`,
`watchdog.sh`, `test-backup-restore-locally.sh`) were removed with that
move. ADR-030 records the policy intent is unchanged and points here for
the replacement mechanism. The mechanism itself shipped in #994; this
amendment records it and closes the two gaps that #994 left: the ADR was
never updated, and automated recurring restore verification was dropped.

### Decision

Keep the GC-P021 policy; replace the AWS mechanism with a tailnet-native
one. The single logical layer (`pg_dump -Fc`) is retained; the EBS
block-level layer has no on-prem equivalent and GC-P021 never required
it, because `AgeGraphService.materializeGraph()` re-derives the AGE graph
from the restored relational tables (see the original Context).

1. **Local dump.** `deploy/scripts/backup.sh` runs `pg_dump -Fc` inside
   the live `gc-db-1` container to `/data/backups/gc-<UTC-timestamp>.dump`.
   It authenticates via the container's peer auth, so it reads no secret
   and never touches `/opt/gc/.env`.
2. **Off-box durability.** The same script rsyncs each dump over the
   tailnet to `gc-backup@aurora:/var/backups/groundcontrol/`. The aurora
   side pins the key to an `rrsync` forced command
   (`deploy/scripts/aurora-setup-gc-backup.sh`), so the key can do nothing
   but land payloads in that directory. rsync failure logs `WARN` and is
   retried on the next timer fire; the local dump still succeeds. This
   replaces the S3 upload.
3. **Cadence.** `gc-backup.timer` fires at 03:00, 11:00, 19:00 UTC
   (≥ 3×/day, `Persistent=true`). This replaces the `0 3,11,19 * * *`
   cron entry.
4. **Retention.** `GC_BACKUP_KEEP_DAYS` (default 30) drops dumps older
   than the window. At 3×/day that keeps roughly 90 dumps, well over the
   GC-P021 24 h floor. Retention is now days-based; the old count-based
   `LOCAL_RETENTION_COUNT` is retired to keep one canonical definition.
5. **Restore verification.** `deploy/scripts/test-restore.sh`, scheduled
   daily by `gc-restore-test.timer` at 05:00 UTC, restores the most
   recent dump into a throwaway `apache/age:release_PG16_1.6.0` container
   (never the live `gc-db-1`) and asserts the six operational-readiness
   sentinels: ≥ 1 public table, a non-empty `flyway_schema_history`, the
   V010 `create_age_graph` migration, the AGE extension, the core Ground
   Control tables, and a live `create_graph()` against the restored
   catalog. The sentinels are the authoritative gate, so a partial or
   truncated dump that `pg_restore` accepted with warnings still fails.
   Any failure exits non-zero, `gc-restore-test.service` fails, and the
   journal entry is the paging signal. Unlike the AWS copy, the script
   reads no secret: the throwaway container is initialised with a
   container-local random password and only the non-secret
   `POSTGRES_USER`/`POSTGRES_DB` defaults, so the hardened `gc-backup`
   identity keeps zero access to `/opt/gc/.env`.
6. **Identity + hardening.** Both `gc-backup.service` and
   `gc-restore-test.service` run as the dedicated `gc-backup` system user
   (nologin, in the `docker` group) under `NoNewPrivileges`,
   `PrivateTmp`, `ProtectSystem=strict`, scoped `ReadWritePaths`, and
   finite `TimeoutStartSec`. Backup duties are deliberately NOT granted to
   `gc-deploy`; deploy and backup are separate trust boundaries.
7. **Rollout.** `deploy/scripts/install-gc-backup.sh` is the idempotent
   red-dragon installer (user, key, `/opt/gc/{backup,test-restore}.sh`,
   the four systemd units, `enable --now` on both timers). It replaces the
   SSM-driven `install-ops-scripts.sh`. The repo copy is the source of
   truth; the operator copies it onto the host and re-runs it on change.
8. **Documentation.** `docs/operations/backup-restore.md` is the
   cold-recovery runbook (restore-in-place, off-box restore, full host
   rebuild, AGE rematerialise, post-restore verification, and the
   automated restore-test drill), written for an operator with no prior
   exposure to Ground Control, satisfying the "recovery without prior
   knowledge" clause of GC-P021.
9. **Drift protection.** `scripts/assert-backup-policy.sh` is the
   structural gate over all eight artifacts. It runs in pre-commit and
   `make policy` and fails if any artifact is missing, drops its GC-P021
   anchor, falls below ≥ 3×/day backup or ≥ 1×/day restore-test cadence,
   stops running as `gc-backup`, drops the rsync target or the aurora
   `rrsync` forced command, or hollows out the restore-test's AGE-aware
   verification depth.

### Consequences

- **Positive.** No AWS surface (no IAM, S3 lifecycle, DLM, or SSM to keep
  in sync). One operational model: every workload is a tailnet host.
  Automated daily restore verification is restored, keeping the GC-P021
  "verified on a recurring basis" clause met by automation rather than
  operator discipline. The restore test needs no secret access, a strict
  improvement over the AWS copy.
- **Negative.** Off-box durability now depends on aurora reachability over
  the tailnet; an aurora outage delays (does not lose) the off-box copy,
  and repeated rsync `WARN` lines are the only signal that aurora access
  drifted, so paging must inspect journal content, not just unit exit
  status. There is no managed snapshot service; rsync retention on aurora
  is operator-verified.
- **Risk: AGE image drift.** The restore test pins
  `apache/age:release_PG16_1.6.0`, matching the `db` service in
  `deploy/docker/docker-compose.prod.yml`. A production image bump must
  bump the test image in lockstep; a mismatch can let a dump taken
  pre-upgrade fail to restore post-upgrade. A version upgrade touches both
  and warrants revisiting this ADR.

## Related

- [ADR-005 Apache AGE](005-apache-age-graph.md)
- [ADR-018 AWS EC2 Deployment](018-aws-ec2-deployment.md) - superseded by ADR-030
- [ADR-030 On-prem Hetzner Deployment](030-on-prem-hetzner-deployment.md) - the move that drove this amendment
- GC-P009 - the enabling capability (see Ground Control requirement catalog)
- GC-P021 - the policy this ADR records, filed as issue [#533](https://github.com/KeplerOps/Ground-Control/issues/533); on-prem mechanism wired up in [#978](https://github.com/autarchy-ai/Ground-Control/issues/978)
- [docs/operations/backup-restore.md](../../docs/operations/backup-restore.md) - the operator runbook
- Canonical artifacts: `deploy/scripts/{backup,test-restore,install-gc-backup,aurora-setup-gc-backup}.sh`, `deploy/systemd/gc-backup.{service,timer}`, `deploy/systemd/gc-restore-test.{service,timer}`, `scripts/assert-backup-policy.sh`
