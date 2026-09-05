---
id: GC-P009
title: "Data Backup"
status: DEPRECATED
type: NON_FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-15T19:58:58.692264Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-P009 — Data Backup

## Statement

The system shall support configurable automated backups of all persistent data with defined retention and point-in-time restore capability. Backup frequency and retention period shall be configurable. Restore shall be documented and testable without production impact.

## Rationale

A remotely hosted system managing authoritative requirements data must protect against data loss. Backups are table stakes for any production deployment. Configurable schedule and retention avoids one-size-fits-all assumptions about RPO.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `457` (GC-P009: Data Backup)

## Historical traceability

Links below named artifacts the #1500 re-platform deleted. They are kept for
provenance and are outside the parsed `## Traceability` section, so no tool reads
them as live evidence. Do not infer current implementation from them.

- DOCUMENTS → DOCUMENTATION `docs/deployment/DEPLOYMENT.md` (Backup/restore documentation with PITR and testing procedures)
- IMPLEMENTS → CODE_FILE `deploy/terraform/modules/backup/variables.tf` (Backup module variables - configurable frequency and retention)
- IMPLEMENTS → CODE_FILE `deploy/terraform/modules/compute/user-data.sh.tftpl` (EC2 user-data - backup/restore/test-restore scripts and cron)
- IMPLEMENTS → CODE_FILE `deploy/scripts/restore.sh` (Restore script - automated restore from dump or S3)
- TESTS → TEST `deploy/scripts/test-restore.sh` (Restore test - non-destructive backup validation)
