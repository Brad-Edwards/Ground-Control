---
id: GC-A013
title: "Project Scoping"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-14T02:52:45.073701Z
updated_at: 2026-03-16T07:57:00.137423Z
---

# GC-A013 — Project Scoping

## Statement

The system shall support a Project entity with name, identifier, and description. Every requirement shall belong to exactly one project. Relations between requirements shall be constrained to the same project. All listing, filtering, analysis, and traceability operations shall default to single-project scope.

## Rationale

A single Ground Control instance must manage requirements for multiple independent software projects. Without project scoping, requirements from different projects pollute each other's namespace, analysis results, and traceability graphs, making the system unusable for more than one project at a time.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/016-project-scoping.md` (ADR-016: Project Scoping)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tools: gc_list_projects, gc_create_project, project param on 17 tools)
- DOCUMENTS → ADR `016-project-scoping` (ADR-016: Project Scoping)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/projects/model/Project.java` (Project entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/projects/service/ProjectService.java` (Project service (CRUD, resolve))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/projects/ProjectController.java` (Project REST API)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V012__create_project_and_scope_requirements.sql` (Flyway migration: project table and requirement scoping)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ProjectServiceTest.java` (ProjectService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ProjectControllerTest.java` (ProjectController unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java` (Cross-project relation validation test)
