---
id: GC-RSCH-F003
title: "FR-3 Maintain an explicit state machine for phases and prevent downstream phases from running when required upstream artif..."
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.227228Z
updated_at: 2026-06-27T15:05:25.971959Z
---

# GC-RSCH-F003 — FR-3 Maintain an explicit state machine for phases and prevent downstream phases from running when required upstream artif...

## Statement

Maintain an explicit state machine for phases and prevent downstream phases from running when required upstream artifacts are missing.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1000` (Research run lifecycle and phase gating)
- DOCUMENTS → GITHUB_ISSUE `1004` (Research REST and MCP tool surface)
- DOCUMENTS → GITHUB_ISSUE `1031` (Research workspace UI and dashboard)
- DOCUMENTS → ADR `architecture/adrs/072-research-rest-and-mcp-tool-surface.md` (ADR-072 — Research REST and MCP Tool Surface (governs how the F003 phase state machine is exposed via REST/MCP))

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchRunService.java` (ResearchRunService — stage state machine + prerequisite/gate enforcement)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunServiceTest.java` (ResearchRunServiceTest — prerequisite-missing and pending-gate blocking tests)
