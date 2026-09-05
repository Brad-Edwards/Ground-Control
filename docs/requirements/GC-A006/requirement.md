---
id: GC-A006
title: "Audit History"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:28.484985Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-A006 — Audit History

## Statement

The system shall maintain a complete audit history of all requirement mutations including field changes, status transitions, and relation changes, recording who changed what and when.

## Rationale

Requirements evolve over time. Understanding the history of changes is essential for traceability, accountability, and understanding design decisions. ADR-011 specifies Envers auditing.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#298` (GC-A006: Audit History)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (Audit history REST endpoint (GET /{id}/history))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AuditService.java` (AuditService - Envers revision query logic)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/audit/GroundControlRevisionEntity.java` (Custom revision entity with actor tracking)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AuditHistoryIntegrationTest.java` (Audit history endpoint integration test)
