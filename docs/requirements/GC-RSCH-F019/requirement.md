---
id: GC-RSCH-F019
title: "FR-19 Apply charting forms with field-level provenance"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 13
created_at: 2026-05-25T22:47:46.489207Z
updated_at: 2026-05-26T01:48:23.090645Z
---

# GC-RSCH-F019 — FR-19 Apply charting forms with field-level provenance

## Statement

Apply charting forms with field-level provenance: source section/page/span, quote or paraphrase, uncertainty, and reviewer/agent identity.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1002` (Research full-text, screening, charting, and evidence matrices)
- DOCUMENTS → GITHUB_ISSUE `1015` (Research PDF/OCR/text extraction with location preservation)
- DOCUMENTS → GITHUB_ISSUE `1016` (Research charting schema, pilot coding, and evidence spans)
- DOCUMENTS → GITHUB_ISSUE `1021` (Research full-text evidence Q&A adapter)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CODE_FILE `skills/lit-review-search/SKILL.md` (lit-review-search skill — charting forms with field-level provenance (Workflow §5))
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchProvenanceNode.java` (CHARTING_CELL provenance node — field-level provenance (locator/role/summary, server actor) (ADR-069))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchProvenanceServiceTest.java` (Provenance service tests — charting-cell node provenance)
