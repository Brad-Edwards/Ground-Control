---
id: GC-O005
title: "Requirement-Before-Code Policy"
status: DEPRECATED
type: CONSTRAINT
priority: SHOULD
wave: 2
created_at: 2026-03-14T01:24:26.169457Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-O005 — Requirement-Before-Code Policy

## Statement

The system shall enforce that a requirement must exist and be in ACTIVE status before an IMPLEMENTS traceability link can be created against it, preventing retroactive justification of unplanned work.

## Rationale

Without this constraint, traceability links are created after the fact to satisfy coverage gates, rather than requirements driving implementation. Requirement-before-code ensures the planning-to-execution flow is forward, not backward. This prevents the common failure mode where requirements are written to document what was already built rather than to specify what should be built.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `460` (GC-O005: Requirement-Before-Code Policy)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/TraceabilityService.java` (TraceabilityService - ACTIVE status check for IMPLEMENTS links)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TraceabilityServiceTest.java` (TraceabilityService tests - status enforcement for IMPLEMENTS links)
