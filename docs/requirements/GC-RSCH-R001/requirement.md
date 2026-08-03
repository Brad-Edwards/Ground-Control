---
id: GC-RSCH-R001
title: "R-1 Distinguish methodology selection, protocol planning, source search, screening, charting, synthesis, argument constru..."
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.039076Z
updated_at: 2026-06-27T15:05:23.616365Z
---

# GC-RSCH-R001 — R-1 Distinguish methodology selection, protocol planning, source search, screening, charting, synthesis, argument constru...

## Statement

The system shall distinguish methodology selection, protocol planning, source search, screening, charting, synthesis, argument construction, and prose drafting as separate lifecycle stages.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1000` (Research run lifecycle and phase gating)
- DOCUMENTS → GITHUB_ISSUE `1007` (Research protocol planning artifact)
- DOCUMENTS → ADR `architecture/adrs/056-research-project-type-and-intake.md` (ADR-056 — Research project type and intake metadata (forward-looking: R001 lifecycle phases delivered by subsequent issues on top of this intake foundation))
- DOCUMENTS → GITHUB_ISSUE `999` (Research project type and intake metadata)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunStage.java` (ResearchRunStage — closed eight-stage research lifecycle enum)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunServiceTest.java` (ResearchRunServiceTest — stage-sequence advance tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/ResearchRunLifecycleIntegrationTest.java` (SOURCE_SEARCH blocked-then-allowed protocol-plan gate (distinct stages, #1007))
