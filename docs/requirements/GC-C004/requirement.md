---
id: GC-C004
title: "Orphan Detection"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:53.948479Z
updated_at: 2026-03-18T05:38:38.149305Z
---

# GC-C004 — Orphan Detection

## Statement

The system shall detect orphan requirements — requirements with no incoming or outgoing relations to other requirements — and report them.

## Rationale

Orphan requirements indicate either missing context (they should be related to something) or abandoned work. Surfacing them prevents requirements from being silently forgotten.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - findOrphans)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (AnalysisController - orphan detection endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest - findOrphans unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (AnalysisIntegrationTest - orphan detection integration test)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#318` (Bug: AnalysisService N+1 query pattern in findOrphans() and findCoverageGaps())
- IMPLEMENTS → GITHUB_ISSUE `337` (GC-C004: Orphan Detection)
