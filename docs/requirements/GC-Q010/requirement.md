---
id: GC-Q010
title: "Threat Modeling Workspace"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-30T02:53:41.018327Z
updated_at: 2026-05-29T01:19:13.164871Z
---

# GC-Q010 — Threat Modeling Workspace

## Statement

The web application shall provide a Threat Modeling workspace showing scoped operational assets, boundaries, flows, threat-model entries, linked controls, linked requirements, and stale review indicators.

## Rationale

Threat modeling is spatial and relational. Humans need a workspace that surfaces assets, trust boundaries, flows, and linked mitigations together rather than scattering them across independent records.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `748` (GC-Q010: Threat Modeling Workspace)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/service/ThreatModelWorkspaceService.java` (ThreatModelWorkspaceService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/threatmodels/ThreatModelWorkspaceController.java` (ThreatModelWorkspaceController)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/threat-modeling-workspace.tsx` (Threat Modeling Workspace page)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelWorkspaceServiceTest.java` (ThreatModelWorkspaceServiceTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ThreatModelWorkspaceControllerTest.java` (ThreatModelWorkspaceControllerTest)
- TESTS → TEST `frontend/src/pages/__tests__/threat-modeling-workspace.test.tsx` (Threat Modeling Workspace page tests)
- DOCUMENTS → GITHUB_ISSUE `#748` (GC-Q010: Threat Modeling Workspace)
