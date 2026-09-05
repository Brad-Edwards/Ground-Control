---
id: GC-Q010
title: "Threat Modeling Workspace"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-30T02:53:41.018327Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-Q010 — Threat Modeling Workspace

## Statement

The web application shall provide a Threat Modeling workspace showing scoped operational assets, boundaries, flows, threat-model entries, linked controls, linked requirements, and stale review indicators.

## Rationale

Threat modeling is spatial and relational. Humans need a workspace that surfaces assets, trust boundaries, flows, and linked mitigations together rather than scattering them across independent records.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `748` (GC-Q010: Threat Modeling Workspace)
- DOCUMENTS → GITHUB_ISSUE `#748` (GC-Q010: Threat Modeling Workspace)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/service/ThreatModelWorkspaceService.java` (ThreatModelWorkspaceService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/threatmodels/ThreatModelWorkspaceController.java` (ThreatModelWorkspaceController)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/threat-modeling-workspace.tsx` (Threat Modeling Workspace page)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelWorkspaceServiceTest.java` (ThreatModelWorkspaceServiceTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ThreatModelWorkspaceControllerTest.java` (ThreatModelWorkspaceControllerTest)
- TESTS → TEST `frontend/src/pages/__tests__/threat-modeling-workspace.test.tsx` (Threat Modeling Workspace page tests)
