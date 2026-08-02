---
id: GC-G004
title: "Apache AGE Materialization"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:13:46.483453Z
updated_at: 2026-03-18T05:51:33.197883Z
---

# GC-G004 — Apache AGE Materialization

## Statement

The system shall materialize the requirement DAG into Apache AGE as a read-only graph layer, enabling graph-native queries via openCypher while maintaining the relational database as the source of truth for writes.

## Rationale

ADR-005 commits to Apache AGE for graph capabilities. Materialization provides graph query performance without the operational complexity of a separate graph database. Write ownership stays in the service layer per ADR-011.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/age/AgeGraphService.java` (AgeGraphService - Apache AGE graph materialization)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphController.java` (GraphController - graph query REST endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GraphClient.java` (GraphClient - domain interface for graph operations)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/AgeGraphServiceTest.java` (AgeGraphServiceTest - graph materialization unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AgeGraphServiceIntegrationTest.java` (AgeGraphServiceIntegrationTest - AGE integration tests)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#316` (Bug: GraphController has no class-level @RequestMapping and bypasses service layer)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#319` (Bug: AgeGraphService has insufficient Cypher injection prevention)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#320` (Bug: AgeGraphService materializeGraph rebuilds entire graph with no batching)
- IMPLEMENTS → GITHUB_ISSUE `340` (GC-G004: Apache AGE Materialization)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/age/AgeGraphSnapshotRepository.java` (AgeGraphSnapshotRepository - AGE graph snapshot pointer/metadata persistence)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/AgeGraphSnapshotRepositoryTest.java` (AgeGraphSnapshotRepositoryTest - snapshot insert/retention unit tests)
