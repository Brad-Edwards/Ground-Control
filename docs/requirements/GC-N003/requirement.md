---
id: GC-N003
title: "Structured Requirement Version Diff"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T17:23:26.528562Z
updated_at: 2026-03-24T06:28:04.128873Z
---

# GC-N003 — Structured Requirement Version Diff

## Statement

The system shall provide a structured diff API that returns a machine-readable change set between two versions of a single requirement, enumerating per-field changes (field name, old value, new value), added/removed relations, and added/removed traceability links. The API shall be exposed via both REST and MCP tools.

## Rationale

GC-N001 stores version history but provides no structured diff output. Agents need machine-readable diffs to reason about requirement evolution — "what changed since last review?", "which fields were modified?" A per-requirement diff is the building block for baseline-level diffs and automated change review workflows.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AuditService.java` (AuditService - getRequirementDiff())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (RequirementController - GET /{id}/diff REST endpoint)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool gc_get_requirement_diff)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/domain/requirements/service/AuditServiceDiffTest.java` (Unit tests for version diff)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RequirementControllerTest.java` (Controller tests for diff endpoint (VersionDiff nested class))
- IMPLEMENTS → GITHUB_ISSUE `#230` (GC-N003: Structured Requirement Version Diff)
