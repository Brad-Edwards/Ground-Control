---
id: GC-G001
title: "Ancestor-Descendant Traversal"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:13:37.477824Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-G001 — Ancestor-Descendant Traversal

## Statement

The system shall support ancestor and descendant traversal of the requirement DAG with configurable depth limits, returning all requirements reachable from a given starting point.

## Rationale

Hierarchical navigation is the most common graph operation. Depth-limited traversal prevents unbounded result sets while enabling both local and global views of the requirement hierarchy.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#316` (Bug: GraphController has no class-level @RequestMapping and bypasses service layer)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#319` (Bug: AgeGraphService has insufficient Cypher injection prevention)
- IMPLEMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#368` (GC-G001: Ancestor-Descendant Traversal)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/GraphController.java` (GraphController - Ancestor/descendant traversal endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/GraphClient.java` (GraphClient - Domain port for ancestor/descendant traversal)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/age/AgeGraphService.java` (AgeGraphService - AGE/Cypher ancestor/descendant traversal implementation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GraphControllerTest.java` (GraphController unit tests - ancestor/descendant endpoints)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/AgeGraphServiceTest.java` (AgeGraphService unit tests - Cypher query construction)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AgeGraphServiceIntegrationTest.java` (AgeGraphService integration tests - Testcontainers PostgreSQL+AGE)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementsE2EAgeIntegrationTest.java` (E2E integration test - ancestor/descendant traversal via REST API)
