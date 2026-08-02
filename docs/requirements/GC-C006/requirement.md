---
id: GC-C006
title: "Transitive Impact Analysis"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:12:00.219755Z
updated_at: 2026-03-18T05:43:45.198247Z
---

# GC-C006 — Transitive Impact Analysis

## Statement

The system shall compute transitive impact sets: given a requirement, return all requirements reachable via directed relations, showing the full blast radius of a change.

## Rationale

Changes to one requirement cascade through the dependency graph. Impact analysis prevents unintended consequences by making the full scope of a change visible before committing to it.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - impactAnalysis)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (AnalysisController - impact analysis endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest - impactAnalysis unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (AnalysisIntegrationTest - impact analysis integration test)
- IMPLEMENTS → GITHUB_ISSUE `339` (GC-C006: Transitive Impact Analysis)
