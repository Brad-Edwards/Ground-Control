---
id: GC-E003
title: "Bidirectional Artifact Navigation"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:12:56.038142Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-E003 — Bidirectional Artifact Navigation

## Statement

The system shall support bidirectional navigation of traceability links: from a requirement to all its linked artifacts, and from an artifact to all requirements it is linked to.

## Rationale

Developers work from both directions: 'what implements this requirement?' and 'which requirements does this file satisfy?' Both queries must be efficient and first-class.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#667` (GC-E003: Bidirectional Artifact Navigation)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/TraceabilityService.java` (TraceabilityService - Bidirectional artifact navigation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TraceabilityServiceTest.java` (TraceabilityServiceTest - findByArtifact (reverse navigation))
