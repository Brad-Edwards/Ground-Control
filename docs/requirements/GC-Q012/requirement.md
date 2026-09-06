---
id: GC-Q012
title: "Evidence and State Explorer"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-30T02:53:41.215875Z
updated_at: 2026-06-13T06:08:49.844483Z
---

# GC-Q012 — Evidence and State Explorer

## Statement

The web application shall provide an Evidence and State Explorer for browsing observations, evidence artifacts, freshness, provenance, affected assets, linked controls, and downstream assessment or finding impact.

## Rationale

Once assets and controls are grounded in observations and evidence, users need a human-readable way to inspect provenance, freshness, and downstream impact. Without this, continuous assurance becomes opaque and untrustworthy.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#750` (GC-Q012: Evidence and State Explorer)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP evidence state workspace client)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP evidence state workspace tool)
- DOCUMENTS → DOCUMENTATION `docs/architecture/ARCHITECTURE.md` (Evidence state workspace architecture documentation)
- DOCUMENTS → ADR `architecture/adrs/054-documentation-coverage-gate.md` (ADR-054 documentation coverage gate amendment)

## Historical traceability

Links below named artifacts the #1500 re-platform deleted. They are kept for
provenance and are outside the parsed `## Traceability` section, so no tool reads
them as live evidence. Do not infer current implementation from them.

- DOCUMENTS → DOCUMENTATION `docs/API.md` (Evidence state workspace API documentation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/evidencestate/EvidenceStateWorkspaceController.java` (Evidence state workspace REST controller)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/evidencestate/EvidenceStateWorkspaceResponse.java` (Evidence state workspace API response)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidencestate/service/EvidenceStateWorkspaceService.java` (Evidence state workspace service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidencestate/service/EvidenceStateWorkspaceResult.java` (Evidence state workspace service result)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/evidence-state-explorer.tsx` (Evidence state explorer page)
- IMPLEMENTS → CODE_FILE `frontend/src/hooks/use-evidence-state-workspace.ts` (Evidence state workspace frontend query hook)
- IMPLEMENTS → CODE_FILE `frontend/src/routes.tsx` (Evidence state explorer route wiring)
- IMPLEMENTS → CODE_FILE `frontend/src/components/layout/app-layout.tsx` (Evidence state explorer navigation entry)
- IMPLEMENTS → CODE_FILE `frontend/src/types/api.ts` (Evidence state workspace frontend API types)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/EvidenceStateWorkspaceControllerTest.java` (Evidence state workspace controller tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/EvidenceStateWorkspaceServiceTest.java` (Evidence state workspace service tests)
- TESTS → TEST `frontend/src/pages/__tests__/evidence-state-explorer.test.tsx` (Evidence state explorer page tests)
- TESTS → TEST `mcp/ground-control/gc-evidence.test.js` (MCP evidence state workspace tests)
