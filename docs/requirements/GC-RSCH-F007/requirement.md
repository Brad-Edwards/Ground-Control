---
id: GC-RSCH-F007
title: "FR-7 Extract formal requirements from methodology sources without filling domain answers into phase-1 output"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.293930Z
updated_at: 2026-05-26T01:48:08.148026Z
---

# GC-RSCH-F007 — FR-7 Extract formal requirements from methodology sources without filling domain answers into phase-1 output

## Statement

Extract formal requirements from methodology sources without filling domain answers into phase-1 output.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1005` (Research methodology catalog and primary-source tracking)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CODE_FILE `skills/lit-review/SKILL.md` (lit-review skill — requirements extraction without domain fill (Workflow §4 + Output structure))
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunMethodologySelection.java` (Method/source-only data shape (no domain-answer fields) — F007)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunMethodologySource.java` (Source rows carry only method/source fields (no domain answers) — F007)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunMethodologyServiceTest.java` (Service tests exercise the method/source data shape)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/MethodologyRequirementsContractEntry.java` (Contract entry holds extracted formal requirements (REQUIREMENT kind), no domain-answer fields (F-7))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunMethodologyContractServiceTest.java` (Contract service tests: no-domain-answer field rejection (F-7))
- DOCUMENTS → GITHUB_ISSUE `1006` (Research methodology requirements artifact)
