---
id: GC-A003
title: "Typed DAG Relations"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:20.914063Z
updated_at: 2026-03-14T05:16:38.220451Z
---

# GC-A003 — Typed DAG Relations

## Statement

The system shall support typed directed relations between requirements: parent, depends_on, refines, conflicts_with, supersedes, and related.

## Rationale

Requirements exist in a dependency graph. Typed relations capture the semantics of how requirements relate — hierarchy (parent), ordering (depends_on), detail (refines), contradiction (conflicts), and evolution (supersedes).

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/RelationType.java` (RelationType enum — all 6 typed relation values)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java` (Unit tests for SUPERSEDES and RELATED relation creation)
- DOCUMENTS → GITHUB_ISSUE `#190` (GC-A003: Typed DAG Relations — Activate requirement)
- IMPLEMENTS → GITHUB_ISSUE `#194` (GC-A003: Typed DAG Relations)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#321` (Bug: RequirementService.getRelations() mutates JPA result list)
