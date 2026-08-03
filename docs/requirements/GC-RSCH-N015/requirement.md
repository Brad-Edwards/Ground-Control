---
id: GC-RSCH-N015
title: "NFR-15 Maintainability"
status: DRAFT
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:47.025309Z
updated_at: 2026-05-25T22:47:47.025309Z
---

# GC-RSCH-N015 — NFR-15 Maintainability

## Statement

Maintainability: prompts, schemas, requirements, and workflow policies shall be versioned and regression tested because small changes can alter scientific behavior.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1005` (Research methodology catalog and primary-source tracking)
- DOCUMENTS → GITHUB_ISSUE `1026` (Research automated review pipeline)
- DOCUMENTS → GITHUB_ISSUE `1027` (Research evaluation harnesses)
- DOCUMENTS → GITHUB_ISSUE `1029` (Research adapter/plugin boundary)
- DOCUMENTS → ADR `architecture/adrs/077-research-behavior-versioning-and-regression-tests.md` (ADR-077: behaviour versioning — #1005 ships the profile/catalog-version snapshot facet)
