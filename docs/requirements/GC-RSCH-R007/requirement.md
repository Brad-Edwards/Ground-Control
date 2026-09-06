---
id: GC-RSCH-R007
title: "R-7 Distinguish literature-based work from experiment-running auto-research"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.168929Z
updated_at: 2026-05-26T06:20:18.302130Z
---

# GC-RSCH-R007 — R-7 Distinguish literature-based work from experiment-running auto-research

## Statement

The system shall distinguish literature-based work from experiment-running auto-research. Reactor's immediate extension target is literature and writing automation, not wet-lab or compute-discovery automation.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1029` (Research adapter/plugin boundary)
- DOCUMENTS → ADR `architecture/adrs/056-research-project-type-and-intake.md` (ADR-056 — Research project type and intake metadata)
- IMPLEMENTS → PULL_REQUEST `1044` (PR #1044 — Research project type + intake metadata)
- DOCUMENTS → GITHUB_ISSUE `999` (Research project type and intake metadata)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/projects/model/ProjectType.java` (ProjectType enum — RESEARCH literal distinguishes literature-based work from other project types)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java` (MigrationSmokeTest — V131 project.type NOT NULL column probe + V132 research_intake_audit column shape probes)
