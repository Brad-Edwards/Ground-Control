---
id: GC-RSCH-F024
title: "FR-24 Produce evidence matrices linking source IDs to charted fields, codes, and synthesis claims"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 13
created_at: 2026-05-25T22:47:46.564893Z
updated_at: 2026-05-26T01:48:28.164038Z
---

# GC-RSCH-F024 — FR-24 Produce evidence matrices linking source IDs to charted fields, codes, and synthesis claims

## Statement

Produce evidence matrices linking source IDs to charted fields, codes, and synthesis claims.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1002` (Research full-text, screening, charting, and evidence matrices)
- DOCUMENTS → GITHUB_ISSUE `1018` (Research evidence matrix and numerical synthesis)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CODE_FILE `skills/lit-review-search/SKILL.md` (lit-review-search skill — evidence matrices (Output evidence-matrix.md))
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchProvenanceEdge.java` (EVIDENCE_MATRIX_CELL edges — link source IDs to charted fields/claims via SUPPORTS/DERIVED_FROM (ADR-069))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchProvenanceServiceTest.java` (Provenance service tests — evidence-matrix edge linking)
