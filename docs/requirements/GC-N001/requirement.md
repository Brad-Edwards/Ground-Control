---
id: GC-N001
title: "Requirement Versioning"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-13T23:15:02.842983Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-N001 — Requirement Versioning

## Statement

The system shall maintain version history for requirements, enabling retrieval of any previous version of a requirement's fields and metadata.

## Rationale

Requirements evolve. Version history enables understanding the evolution of a requirement, supporting diff views and rollback decisions. Envers provides the persistence mechanism per ADR-011.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `336` (GC-N001: Requirement Versioning)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/audit/GroundControlRevisionEntity.java` (GroundControlRevisionEntity - Envers revision tracking)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/audit/AuditService.java` (AuditService - Requirement version history)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (RequirementController - history REST endpoints)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AuditHistoryIntegrationTest.java` (AuditHistoryIntegrationTest - versioning integration tests)
