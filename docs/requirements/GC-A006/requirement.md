---
id: GC-A006
title: "Audit History"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:28.484985Z
updated_at: 2026-03-15T06:50:45.412219Z
---

# GC-A006 — Audit History

## Statement

The system shall maintain a complete audit history of all requirement mutations including field changes, status transitions, and relation changes, recording who changed what and when.

## Rationale

Requirements evolve over time. Understanding the history of changes is essential for traceability, accountability, and understanding design decisions. ADR-011 specifies Envers auditing.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (Audit history REST endpoint (GET /{id}/history))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AuditService.java` (AuditService - Envers revision query logic)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/audit/GroundControlRevisionEntity.java` (Custom revision entity with actor tracking)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AuditHistoryIntegrationTest.java` (Audit history endpoint integration test)
- IMPLEMENTS → GITHUB_ISSUE `#298` (GC-A006: Audit History)
