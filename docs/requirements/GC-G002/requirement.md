---
id: GC-G002
title: "Path Finding"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:13:40.202997Z
updated_at: 2026-03-21T23:33:58.875486Z
---

# GC-G002 — Path Finding

## Statement

The system shall support finding all paths between any two requirements in the DAG, showing how they are connected through intermediate requirements and relations.

## Rationale

Understanding how two requirements are connected reveals hidden dependencies and helps assess whether a proposed change to one could affect the other.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GraphAlgorithms.java` (GraphAlgorithms - Path finding between requirements)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphController.java` (GraphController - findPaths endpoint)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/age/AgeGraphService.java` (AgeGraphService - findPaths with relation types via Cypher)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AgeGraphServiceIntegrationTest.java` (AgeGraphServiceIntegrationTest - findPaths with edge labels)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/PathResult.java` (PathResult - domain record for path with edge labels)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GraphControllerTest.java` (GraphControllerTest - findPaths endpoint with edges)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/AgeGraphServiceTest.java` (AgeGraphServiceTest - findPaths queries with label(r))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/PathResponse.java` (PathResponse - API DTO for path finding with edges)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GraphClient.java` (GraphClient - findPaths domain port)
- IMPLEMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#369` (GC-G002: Path Finding)
