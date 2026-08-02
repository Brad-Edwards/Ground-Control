---
id: GC-RSCH-F036
title: "FR-36 Support checkpoint/resume after every material action"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:46.758276Z
updated_at: 2026-06-27T15:05:27.451467Z
---

# GC-RSCH-F036 — FR-36 Support checkpoint/resume after every material action

## Statement

Support checkpoint/resume after every material action.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1000` (Research run lifecycle and phase gating)
- DOCUMENTS → GITHUB_ISSUE `1028` (Research checkpointing, observability, and budgets)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunArtifact.java` (ResearchRunArtifact — idempotent manifest rows for checkpoint/resume)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunServiceTest.java` (ResearchRunServiceTest — idempotent-record + resume-without-duplication tests)
