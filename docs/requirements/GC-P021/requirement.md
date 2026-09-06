---
id: GC-P021
title: "Ground Control Deployment Backup Policy"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
created_at: 2026-04-19T19:04:37.954743Z
updated_at: 2026-04-20T01:07:29.932877Z
---

# GC-P021 — Ground Control Deployment Backup Policy

## Statement

The Ground Control production deployment shall have its persistent data backed up at least three times per day, with at least twenty-four (24) hours of backup retention. Backups shall cover all persistent state required to restore the system to an operational condition. Restoration from backup shall be verified on a recurring basis and documented such that recovery can be performed without prior knowledge of the live system.

## Rationale

Ground Control holds authoritative requirements, traceability, risk, and governance data across projects. Loss of this data is unrecoverable outside of backups. A minimum cadence of three backups per day caps the Recovery Point Objective at approximately eight hours, and one day of retention provides a minimum recovery window against corruption or accidental deletion while keeping operational overhead low for a single-tenant deployment. GC-P009 establishes the capability; this requirement sets the operational policy the deployment must meet.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/025-backup-policy.md` (ADR-025: Backup Policy (GC-P021))
- IMPLEMENTS → GITHUB_ISSUE `978` (Wire up red-dragon postgres backups per ADR-030 (cron pg_dump + rsync-to-aurora))

## Historical traceability

Links below named artifacts the #1500 re-platform deleted. They are kept for
provenance and are outside the parsed `## Traceability` section, so no tool reads
them as live evidence. Do not infer current implementation from them.

- IMPLEMENTS → DOCUMENTATION `docs/operations/backup-restore.md` (Operator runbook (recovery without prior knowledge))
- IMPLEMENTS → CODE_FILE `deploy/scripts/backup.sh` (pg_dump backup script (runs at the GC-P021 cadence))
- IMPLEMENTS → CODE_FILE `deploy/scripts/test-restore.sh` (Daily restore verification (AGE-aware; runs GC-P021 recurring check))
- IMPLEMENTS → POLICY `scripts/assert-backup-policy.sh` (Pre-commit guardrail enforcing GC-P021 defaults)
- IMPLEMENTS → CODE_FILE `deploy/scripts/install-gc-backup.sh` (Idempotent red-dragon installer (gc-backup user, key, scripts, systemd units) — replaces install-ops-scripts.sh)
- IMPLEMENTS → CODE_FILE `deploy/scripts/aurora-setup-gc-backup.sh` (Aurora-side rrsync forced-command setup for off-box durability (GC-P021))
- IMPLEMENTS → CONFIG `deploy/systemd/gc-backup.service` (Hardened gc-backup oneshot unit (runs pg_dump + rsync-to-aurora))
- IMPLEMENTS → CONFIG `deploy/systemd/gc-backup.timer` (Backup cadence ≥ 3×/day (03/11/19 UTC) — GC-P021)
- IMPLEMENTS → CONFIG `deploy/systemd/gc-restore-test.service` (Hardened restore-test oneshot unit (throwaway-container restore drill))
- IMPLEMENTS → CONFIG `deploy/systemd/gc-restore-test.timer` (Restore-verification cadence ≥ 1×/day (05:00 UTC) — GC-P021 recurring check)
