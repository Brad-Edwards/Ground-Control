---
id: GC-RSCH-F006
title: "FR-6 Read every primary methodology source required by the catalog before producing methodology requirements"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.277888Z
updated_at: 2026-05-26T01:48:05.570337Z
---

# GC-RSCH-F006 — FR-6 Read every primary methodology source required by the catalog before producing methodology requirements

## Statement

Read every primary methodology source required by the catalog before producing methodology requirements.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1005` (Research methodology catalog and primary-source tracking)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CODE_FILE `skills/lit-review/SKILL.md` (lit-review skill — mandatory primary-source reading (Workflow §3))
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchRunService.java` (requireMethodologySourceCoverageComplete gate on METHODOLOGY_REQUIREMENTS (F006))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/MethodologyCatalog.java` (Backend-owned catalog: derives the required primary-source set (F006))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunMethodologySource.java` (Per-source coverage row (required flag + read state))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/MethodologySourceState.java` (Source lifecycle state ATTEMPTED/OBTAINED/READ/BLOCKED)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunMethodologyServiceTest.java` (Service tests: coverage gate (required-not-READ blocks, BLOCKED conflict, optional passes))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ResearchRunMethodologyControllerTest.java` (Controller slice tests: source record/transition + catalog endpoints)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/MethodologyCatalogTest.java` (Catalog load/validate + required-source derivation tests)
