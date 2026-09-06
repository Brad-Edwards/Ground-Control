---
id: GC-RSCH-F001
title: "FR-1 Capture research goal, paper context, target contribution type, intended output, autonomy level, allowed tools, priva..."
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.187382Z
updated_at: 2026-05-26T06:20:16.240560Z
---

# GC-RSCH-F001 — FR-1 Capture research goal, paper context, target contribution type, intended output, autonomy level, allowed tools, priva...

## Statement

Capture research goal, paper context, target contribution type, intended output, autonomy level, allowed tools, privacy constraints, and cost/compute budget.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1031` (Research workspace UI and dashboard)
- DOCUMENTS → ADR `architecture/adrs/056-research-project-type-and-intake.md` (ADR-056 — Research project type and intake metadata)
- IMPLEMENTS → PULL_REQUEST `1044` (PR #1044 — Research project type + intake metadata)
- DOCUMENTS → GITHUB_ISSUE `999` (Research project type and intake metadata)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchIntake.java` (ResearchIntake entity — goal + paperContext + contributionType + intendedOutput + autonomyLevel + allowedTools + privacyConstraints + budget fields)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ResearchIntakeServiceTest.java` (ResearchIntakeServiceTest — covers create / replace / findByProject + every validate() error path)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ProjectControllerTest.java` (ProjectControllerTest — research-flavoured create / get / replace-intake paths with ArgumentCaptor on intake forwarding)
