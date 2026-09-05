---
id: GC-C003
title: "Coverage Gap Analysis"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:51.721503Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-C003 — Coverage Gap Analysis

## Statement

The system shall provide coverage gap analysis parameterized by link type, identifying requirements that lack specific traceability links (for example, requirements with no tests link, no code link, no proof link).

## Rationale

Traceability gaps mean requirements are not verified. Coverage analysis is the primary mechanism for ensuring every requirement has adequate implementation and verification evidence.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#318` (Bug: AnalysisService N+1 query pattern in findOrphans() and findCoverageGaps())
- DOCUMENTS → GITHUB_ISSUE `#665` (GC-C003: Coverage Gap Analysis)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - findCoverageGaps)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest - findCoverageGaps)
