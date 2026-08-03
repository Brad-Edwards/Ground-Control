---
id: GC-RSCH-F017
title: "FR-17 Enforce the two-state rule"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 13
created_at: 2026-05-25T22:47:46.456593Z
updated_at: 2026-05-26T01:48:20.580741Z
---

# GC-RSCH-F017 — FR-17 Enforce the two-state rule

## Statement

Enforce the two-state rule: fully-in sources are resolved, stored, full-text read, and charted; access-gap sources are resolved and stored but not charted.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1014` (Research full-text acquisition and access-gap enforcement)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CODE_FILE `skills/lit-review-search/SKILL.md` (lit-review-search skill — two-state rule (§The discipline))
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))
