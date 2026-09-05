---
id: GC-A004
title: "Unique Relation Constraint"
status: DEPRECATED
type: CONSTRAINT
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:23.132708Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-A004 — Unique Relation Constraint

## Statement

The system shall enforce a unique constraint on (source, target, relation_type) for relations, preventing duplicate relations of the same type between the same pair of requirements.

## Rationale

Duplicate relations are meaningless and create noise in graph queries. Data integrity requires uniqueness at the relation level.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#196` (GC-A004: Unique Relation Constraint)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementServiceIntegrationTest.java`
