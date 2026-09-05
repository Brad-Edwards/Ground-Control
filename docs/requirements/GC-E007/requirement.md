---
id: GC-E007
title: "Link Lifecycle History"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-14T02:45:11.231162Z
updated_at: 2026-03-14T02:45:11.231162Z
---

# GC-E007 — Link Lifecycle History

## Statement

The system shall retain traceability links through their full lifecycle — active, stale, broken, and superseded — as queryable first-class objects, preserving the historical record of which artifacts were linked to a requirement and when that relationship changed.

## Rationale

Deleting a stale or broken link destroys audit-significant information. Knowing that a file used to implement a requirement is itself a traceability fact — it explains why code changed, reveals refactoring patterns, and supports regulatory questions like "what was the implementation evidence at date X." Retained links with lifecycle state enable both current-state queries ("what implements this now?") and historical queries ("what implemented this four months ago?").

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#688` (GC-E007: Link Lifecycle History)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/TraceabilityLink.java` (TraceabilityLink - @Audited lifecycle history)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/audit/AuditService.java` (AuditService - Link lifecycle history queries)
