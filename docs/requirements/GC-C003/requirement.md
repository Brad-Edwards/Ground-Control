---
id: GC-C003
title: "Coverage Gap Analysis"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:51.721503Z
updated_at: 2026-05-16T04:45:06.023370Z
---

# GC-C003 — Coverage Gap Analysis

## Statement

The system shall provide coverage gap analysis parameterized by link type, identifying requirements that lack specific traceability links (e.g., requirements with no tests link, no code link, no proof link).

## Rationale

Traceability gaps mean requirements are not verified. Coverage analysis is the primary mechanism for ensuring every requirement has adequate implementation and verification evidence.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - findCoverageGaps)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest - findCoverageGaps)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#318` (Bug: AnalysisService N+1 query pattern in findOrphans() and findCoverageGaps())
- DOCUMENTS → GITHUB_ISSUE `#665` (GC-C003: Coverage Gap Analysis)
