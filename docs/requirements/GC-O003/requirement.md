---
id: GC-O003
title: "Dogfooding Feedback Loop"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:15:15.515762Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-O003 — Dogfooding Feedback Loop

## Statement

The system shall support capturing dogfooding feedback as linked artifacts on requirements, enabling the development team to track usability issues, missing features, and workflow gaps discovered through self-use.

## Rationale

Dogfooding feedback is the most valuable input for product improvement. Structured capture linked to requirements ensures feedback drives concrete improvements rather than being lost in chat or email.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#304` (GC-O003: Dogfooding Feedback Loop)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/TraceabilityLink.java` (TraceabilityLink entity — supports linking feedback artifacts to requirements)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/TraceabilityService.java` (TraceabilityService — create/query artifact links on requirements)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TraceabilityLinkIntegrationTest.java` (Traceability link integration tests)
