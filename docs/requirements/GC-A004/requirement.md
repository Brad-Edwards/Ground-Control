---
id: GC-A004
title: "Unique Relation Constraint"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:23.132708Z
updated_at: 2026-03-14T05:52:47.892581Z
---

# GC-A004 — Unique Relation Constraint

## Statement

The system shall enforce a unique constraint on (source, target, relation_type) for relations, preventing duplicate relations of the same type between the same pair of requirements.

## Rationale

Duplicate relations are meaningless and create noise in graph queries. Data integrity requires uniqueness at the relation level.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementServiceIntegrationTest.java`
- IMPLEMENTS → GITHUB_ISSUE `#196` (GC-A004: Unique Relation Constraint)
