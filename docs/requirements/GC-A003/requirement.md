---
id: GC-A003
title: "Typed DAG Relations"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:20.914063Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-A003 — Typed DAG Relations

## Statement

The system shall support typed directed relations between requirements: parent, depends_on, refines, conflicts_with, supersedes, and related.

## Rationale

Requirements exist in a dependency graph. Typed relations capture the semantics of how requirements relate — hierarchy (parent), ordering (depends_on), detail (refines), contradiction (conflicts), and evolution (supersedes).

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#190` (GC-A003: Typed DAG Relations — Activate requirement)
- IMPLEMENTS → GITHUB_ISSUE `#194` (GC-A003: Typed DAG Relations)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#321` (Bug: RequirementService.getRelations() mutates JPA result list)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/RelationType.java` (RelationType enum — all 6 typed relation values)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java` (Unit tests for SUPERSEDES and RELATED relation creation)
