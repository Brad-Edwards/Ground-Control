---
id: GC-C001
title: "Cycle Detection"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:11:45.489258Z
updated_at: 2026-03-15T19:39:24.353338Z
---

# GC-C001 — Cycle Detection

## Statement

The system shall detect cycles in the requirement relation DAG and report the cycle members, identifying which requirements and relation types form the cycle.

## Rationale

Cycles in a dependency graph indicate logical contradictions or modeling errors. Early detection prevents downstream analysis from entering infinite loops or producing incorrect results.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/CycleEdge.java` (CycleEdge domain record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/CycleResult.java` (CycleResult domain record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService cycle detection with edge types)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/CycleResponse.java` (CycleResponse API DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (AnalysisController cycles endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisService cycle detection unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AnalysisControllerTest.java` (AnalysisController cycle detection API test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/CycleDetectionPropertyTest.java` (Cycle detection property-based tests)
- IMPLEMENTS → GITHUB_ISSUE `#307` (GC-C001: Cycle Detection)
