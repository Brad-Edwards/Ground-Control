---
id: GC-RSCH-F030
title: "FR-30 Ensure generated prose cites only source IDs present in the evidence database"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:46.654718Z
updated_at: 2026-05-26T01:48:31.677197Z
---

# GC-RSCH-F030 — FR-30 Ensure generated prose cites only source IDs present in the evidence database

## Statement

Ensure generated prose cites only source IDs present in the evidence database.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1023` (Research evidence-constrained drafting)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CODE_FILE `skills/lit-review-draft/SKILL.md` (lit-review-draft skill — references.bib generated from Zotero; Citation pass enforces inline citations key only into included set)
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))
