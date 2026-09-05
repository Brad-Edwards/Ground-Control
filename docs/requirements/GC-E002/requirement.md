---
id: GC-E002
title: "Typed Link Semantics"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:12:53.367746Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-E002 — Typed Link Semantics

## Statement

The system shall support typed traceability link semantics including: implements, tests, documents, constrains, and verifies, capturing the nature of the relationship between a requirement and its linked artifact.

## Rationale

Not all artifact links are equal. A test link means something different from an implements link. Typed semantics enable precise queries like 'which requirements lack test coverage' vs 'which lack implementation.'

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#314` (Bug: Traceability link/relation endpoints ignore parent requirement ID)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#327` (Tech debt: TraceabilityLink has no JML contracts)
- DOCUMENTS → GITHUB_ISSUE `#666` (GC-E002: Typed Link Semantics)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/LinkType.java` (LinkType enum - Typed link semantics)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TraceabilityLinkTest.java` (TraceabilityLinkTest - typed link semantics)
