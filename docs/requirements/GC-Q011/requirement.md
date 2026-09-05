---
id: GC-Q011
title: "Control and Assurance Workspace"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-30T02:53:41.110649Z
updated_at: 2026-06-13T18:54:58.506800Z
---

# GC-Q011 — Control and Assurance Workspace

## Statement

The web application shall provide a Control and Assurance workspace for control catalog browsing, scoped implementations, control tests, observation-backed evidence, effectiveness assessments, exceptions, and owner work queues.

## Rationale

Controls are the operational hinge between policy, engineering, audit, and risk. A graph-native control workspace lets humans understand what each control protects, how it is evidenced, and where it is failing.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (Control assurance workspace MCP API helper)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_control_assurance_workspace MCP tool registration)
- DOCUMENTS → DOCUMENTATION `docs/architecture/ARCHITECTURE.md` (GC-Q011 architecture documentation)
- DOCUMENTS → DOCUMENTATION `mcp/ground-control/README.md` (GC-Q011 MCP tool catalog documentation)
- DOCUMENTS → ADR `architecture/adrs/054-documentation-coverage-gate.md` (ADR-054 GC-Q011 documentation coverage amendment)
- DOCUMENTS → DOCUMENTATION `docs/DOC_STYLE.md` (GC-Q011 documentation style sync note)
- IMPLEMENTS → GITHUB_ISSUE `#749` (GC-Q011: Control and Assurance Workspace)

## Historical traceability

Links below named artifacts the #1500 re-platform deleted. They are kept for
provenance and are outside the parsed `## Traceability` section, so no tool reads
them as live evidence. Do not infer current implementation from them.

- DOCUMENTS → DOCUMENTATION `docs/API.md` (GC-Q011 API documentation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlWorkspaceService.java` (Control workspace composition service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlWorkspaceResult.java` (Control workspace domain read model)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/controls/ControlWorkspaceController.java` (Control workspace REST controller)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/controls/ControlWorkspaceResponse.java` (Control workspace REST response DTO)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/control-assurance-workspace.tsx` (Control assurance workspace page)
- IMPLEMENTS → CODE_FILE `frontend/src/hooks/use-control-assurance-workspace.ts` (Control assurance workspace data hook)
- IMPLEMENTS → CODE_FILE `frontend/src/types/api.ts` (Control assurance workspace frontend API types)
- IMPLEMENTS → CODE_FILE `frontend/src/routes.tsx` (Control assurance workspace route)
- IMPLEMENTS → CODE_FILE `frontend/src/components/layout/app-layout.tsx` (Control workspace navigation entry)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlWorkspaceServiceTest.java` (Control workspace service tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ControlWorkspaceControllerTest.java` (Control workspace controller tests)
- TESTS → TEST `frontend/src/pages/__tests__/control-assurance-workspace.test.tsx` (Control assurance workspace frontend tests)
- TESTS → TEST `mcp/ground-control/gc-control.test.js` (Control assurance workspace MCP adapter tests)
- DOCUMENTS → DOCUMENTATION `changelog.d/749.added.md` (GC-Q011 changelog fragment)
