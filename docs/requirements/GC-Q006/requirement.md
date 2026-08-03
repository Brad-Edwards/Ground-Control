---
id: GC-Q006
title: "Audit History Timeline"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-14T02:59:53.091415Z
updated_at: 2026-03-21T05:38:34.484462Z
---

# GC-Q006 — Audit History Timeline

## Statement

The web application shall provide an audit history view showing a chronological timeline of changes to requirements, including field modifications, status transitions, relation changes, and traceability link changes. The timeline shall support filtering by requirement, change type, and date range, with diff views for field-level changes.

## Rationale

Understanding how and why requirements evolved is essential for architecture reviews and incident investigations. A visual timeline with diffs makes the audit trail navigable — without it, audit history is technically available but practically unusable for anything beyond spot-checking individual records.

## Traceability

- IMPLEMENTS → CODE_FILE `frontend/src/pages/requirement-detail.tsx` (Requirement detail - Audit history timeline tab)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AuditService.java` (AuditService - unified timeline method with diff computation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (Timeline REST endpoint)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP gc_get_timeline tool)
- IMPLEMENTS → CODE_FILE `frontend/src/hooks/use-history.ts` (Timeline React Query hook)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AuditServiceTest.java` (AuditService unit tests - diff computation and snapshot conversion)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RequirementControllerTest.java` (Timeline controller endpoint tests)
- DOCUMENTS → GITHUB_ISSUE `362` (GC-Q006: Audit History Timeline)
