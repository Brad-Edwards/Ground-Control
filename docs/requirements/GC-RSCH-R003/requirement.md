---
id: GC-RSCH-R003
title: "R-3 Support autonomous and copilot modes, with configurable human gates at method, protocol, search, synthesis, and writi..."
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.090282Z
updated_at: 2026-06-27T15:05:25.029282Z
---

# GC-RSCH-R003 — R-3 Support autonomous and copilot modes, with configurable human gates at method, protocol, search, synthesis, and writi...

## Statement

The system shall support autonomous and copilot modes, with configurable human gates at method, protocol, search, synthesis, and writing decisions.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1000` (Research run lifecycle and phase gating)
- DOCUMENTS → GITHUB_ISSUE `1001` (Research decision gates and decision log)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchGateBehavior.java` (ResearchGateBehavior — per-run gate behavior resolved from autonomy level)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunServiceTest.java` (ResearchRunServiceTest — gate-behavior-per-autonomy + gate-resolution tests)
- IMPLEMENTS → GITHUB_ISSUE `1001` (Research decision gates and decision log)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunGateDecisionLog.java` (ResearchRunGateDecisionLog — decision history strengthening configurable gate requirement)
