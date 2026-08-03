---
id: GC-X012
title: "Agent consumes knowledge base during pre-implementation exploration"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:29.128615Z
updated_at: 2026-04-12T19:14:29.128615Z
---

# GC-X012 — Agent consumes knowledge base during pre-implementation exploration

## Statement

Coding agents shall consult the repository knowledge base during pre-implementation exploration, reading pages relevant to the files, modules, and subsystems touched by the planned work, and shall apply the gotchas and conventions documented there when shaping the implementation plan.

## Rationale

Capture without consumption is journaling. Consumption closes the compounding loop: a lesson learned and captured in one run becomes a prevented mistake in the next. Without an explicit obligation to read the knowledge base before planning, the accumulated lessons do not affect behavior and the whole capture mechanism produces no value.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#524` (Knowledge system 3/6: consumption and /implement integration)
