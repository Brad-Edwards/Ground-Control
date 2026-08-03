---
id: GC-X014
title: "/implement workflow consults and contributes to the knowledge base"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:39.963345Z
updated_at: 2026-04-12T19:14:39.963345Z
---

# GC-X014 — /implement workflow consults and contributes to the knowledge base

## Statement

The /implement workflow shall instruct the active agent to consult the repository knowledge base during its exploration phase and to capture observations into the knowledge inbox during review loops, after user corrections, and whenever the agent recognizes a reusable lesson.

## Rationale

The /implement workflow is the most frequent invocation point for an agent and the richest source of knowledge: review findings, fix-review commit reasoning, user corrections, and CI failures all happen inside /implement. Integrating capture into the review loop and consumption into the exploration step makes the habit implicit in the workflow rather than an optional side task.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#524` (Knowledge system 3/6: consumption and /implement integration)
