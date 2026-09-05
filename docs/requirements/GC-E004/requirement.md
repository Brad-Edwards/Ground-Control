---
id: GC-E004
title: "Link Health Tracking"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:12:59.148015Z
updated_at: 2026-03-14T01:09:58.469479Z
---

# GC-E004 — Link Health Tracking

## Statement

The system shall track the health status of traceability links (synced, stale, broken), detecting when linked artifacts have changed or been deleted since the link was last validated.

## Rationale

Traceability links decay as code evolves. A link to a deleted file or significantly refactored code is misleading. Health tracking surfaces stale links before they undermine traceability confidence.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#684` (GC-E004: Link Health Tracking)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/TraceabilityLink.java` (TraceabilityLink - syncStatus and lastSyncedAt fields)
