---
id: GC-J009
title: "ADR Supersession Chain"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T19:45:53.051561Z
updated_at: 2026-03-14T19:45:53.051561Z
---

# GC-J009 — ADR Supersession Chain

## Statement

The system shall support explicit supersession relationships between ADRs: when a new ADR supersedes an older one, the system shall record the supersession link, automatically transition the superseded ADR to "superseded" status, and maintain the full decision chain. The system shall support querying the current active decision for any superseded ADR and the complete decision evolution history.

## Rationale

Architecture decisions evolve. ADR-010 might supersede ADR-003 when the technology landscape changes. Without explicit supersession tracking, teams discover outdated decisions by accident. Decision chains answer "why did we change from X to Y to Z?" — critical context for future decision-making. This reuses the SUPERSEDES relation type already supported in the DAG (per GC-A003).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#287` (GC-J009: ADR Supersession Chain)
