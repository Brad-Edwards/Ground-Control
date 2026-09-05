---
id: GC-RSCH-N011
title: "NFR-11 Observability"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:46.958719Z
updated_at: 2026-06-27T15:05:29.620336Z
---

# GC-RSCH-N011 — NFR-11 Observability

## Statement

Observability: users shall be able to see current phase, pending gates, source counts, errors, access gaps, cost, and artifact readiness.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1004` (Research workflow evaluation, observability, and adapters)
- DOCUMENTS → GITHUB_ISSUE `1000` (Research run lifecycle and phase gating)
- DOCUMENTS → GITHUB_ISSUE `1003` (Research graph projection and traversal support)
- DOCUMENTS → GITHUB_ISSUE `1028` (Research checkpointing, observability, and budgets)
- DOCUMENTS → GITHUB_ISSUE `1031` (Research workspace UI and dashboard)
- DOCUMENTS → ADR `architecture/adrs/056-research-project-type-and-intake.md` (ADR-056 — Research project type and intake metadata (forward-looking: N011 observability needs phases/gates which are delivered by subsequent issues))
- DOCUMENTS → GITHUB_ISSUE `999` (Research project type and intake metadata)
- DOCUMENTS → ADR `architecture/adrs/072-research-rest-and-mcp-tool-surface.md` (ADR-072 — Research REST and MCP Tool Surface (binds N011 observability to the ADR-065 snapshot served via REST/MCP))

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchRunSnapshot.java` (ResearchRunSnapshot — bounded observability read model)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunServiceTest.java` (ResearchRunServiceTest — snapshot composition (stage, pending gates, readiness, counts))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/ResearchGraphProjectionContributor.java` (Run/stage/artifact-readiness observability facet visible in the graph (ADR-070))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/graph/ResearchGraphProjectionContributorTest.java` (Research graph projection tests — run/artifact node properties)
