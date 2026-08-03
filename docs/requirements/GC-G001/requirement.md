---
id: GC-G001
title: "Ancestor-Descendant Traversal"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:13:37.477824Z
updated_at: 2026-03-21T23:13:15.547401Z
---

# GC-G001 — Ancestor-Descendant Traversal

## Statement

The system shall support ancestor and descendant traversal of the requirement DAG with configurable depth limits, returning all requirements reachable from a given starting point.

## Rationale

Hierarchical navigation is the most common graph operation. Depth-limited traversal prevents unbounded result sets while enabling both local and global views of the requirement hierarchy.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphController.java` (GraphController - Ancestor/descendant traversal endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GraphClient.java` (GraphClient - Domain port for ancestor/descendant traversal)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/age/AgeGraphService.java` (AgeGraphService - AGE/Cypher ancestor/descendant traversal implementation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GraphControllerTest.java` (GraphController unit tests - ancestor/descendant endpoints)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/AgeGraphServiceTest.java` (AgeGraphService unit tests - Cypher query construction)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AgeGraphServiceIntegrationTest.java` (AgeGraphService integration tests - Testcontainers PostgreSQL+AGE)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementsE2EAgeIntegrationTest.java` (E2E integration test - ancestor/descendant traversal via REST API)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#316` (Bug: GraphController has no class-level @RequestMapping and bypasses service layer)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#319` (Bug: AgeGraphService has insufficient Cypher injection prevention)
- IMPLEMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#368` (GC-G001: Ancestor-Descendant Traversal)
