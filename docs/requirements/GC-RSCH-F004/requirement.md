---
id: GC-RSCH-F004
title: "FR-4 Provide human gates with recommendation and rationale"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.243926Z
updated_at: 2026-06-28T16:54:15.178276Z
---

# GC-RSCH-F004 — FR-4 Provide human gates with recommendation and rationale

## Statement

Provide human gates with recommendation and rationale. Gates shall be logged immediately in `decisions.md` or equivalent state.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1001` (Research decision gates and decision log)
- IMPLEMENTS → GITHUB_ISSUE `1001` (Research decision gates and decision log)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunGateDecisionLog.java` (ResearchRunGateDecisionLog — human gate decision record with recommendation and rationale)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchRunService.java` (ResearchRunService — resolveGate appends gate decision log entry)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/research/ResearchRunController.java` (ResearchRunController — exposes gate decision endpoints for F004)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunDecisionSurfacesServiceTest.java` (ResearchRunDecisionSurfacesServiceTest — tests gate decision log and human gate behavior)
