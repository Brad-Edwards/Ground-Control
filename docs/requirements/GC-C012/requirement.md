---
id: GC-C012
title: "PR Impact Surfacing"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-14T01:24:26.105904Z
updated_at: 2026-03-14T01:24:26.105904Z
---

# GC-C012 — PR Impact Surfacing

## Statement

The system shall analyze the files changed in a pull request, identify all requirements with traceability links to those files, and surface the affected requirements as structured output suitable for PR comments or status checks.

## Rationale

Developers and reviewers need to know which requirements are affected by a code change at review time, not after merge. Surfacing affected requirements on the PR closes the feedback loop between code changes and requirement traceability before damage is merged.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - Impact analysis (partial PR surfacing))
- DOCUMENTS → GITHUB_ISSUE `#686` (GC-C012: PR Impact Surfacing)
