---
id: GC-S004
title: "CMDB/Asset Evidence Adapter Specification"
status: ACTIVE
type: INTERFACE
priority: COULD
wave: 5
created_at: 2026-03-14T16:56:08.561103Z
updated_at: 2026-06-28T02:02:28.068320Z
---

# GC-S004 — CMDB/Asset Evidence Adapter Specification

## Statement

The system shall specify an evidence adapter for Configuration Management Database and asset management systems (ServiceNow, Snipe-IT, Jamf) capable of collecting: asset inventory, configuration item status, patch levels, software license compliance, and end-of-life tracking.

## Rationale

Asset management evidence supports SOC 2 CC6.6, ISO 27001 A.8, and SOX ITGC. Agent-collected CMDB evidence provides current-state asset posture rather than stale inventory snapshots.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#213` (GC-S004: CMDB/Asset Evidence Adapter Specification)
- DOCUMENTS → DOCUMENTATION `docs/architecture/ARCHITECTURE.md` (Architecture overview — CMDB/asset evidence adapter specification (GC-S004))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/cmdb/CmdbEvidenceProvider.java` (CmdbEvidenceProvider (GC-S004 provider keys))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/cmdb/CmdbEvidenceFamily.java` (CmdbEvidenceFamily (GC-S004 evidence families))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/cmdb/CmdbEvidenceSpecification.java` (CmdbEvidenceSpecification (GC-S004 normative spec))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/CmdbEvidenceAdapterSpecificationTest.java` (CmdbEvidenceAdapterSpecificationTest (GC-S004 conformance))
