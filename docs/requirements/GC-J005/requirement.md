---
id: GC-J005
title: "Architecture Decision Impact Surfacing"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T18:37:39.064835Z
updated_at: 2026-03-14T18:37:39.064835Z
---

# GC-J005 — Architecture Decision Impact Surfacing

## Statement

The system shall, when analyzing files changed in a pull request or development session, identify architectural constraints that govern the modified code areas and surface the relevant ADRs, constraint requirements, and their current enforcement status (passing, failing, stale, unenforced). This extends GC-C012's requirement impact surfacing with architecture-specific context: not just "which requirements does this code satisfy" but "which architectural decisions constrain how this code may be structured."

## Rationale

Architectural drift happens when developers modify constrained code without knowing the constraint exists. GC-C012 surfaces affected requirements at PR time; this requirement extends that pattern to architectural decisions. An agent or developer modifying domain/ code should see "ADR: domain layer must not import infrastructure/ -- enforced by ArchitectureTest -- last passed 2h ago" before the PR is opened, not after the ArchUnit test fails in CI. Surfacing constraints proactively reduces the cost of violations from "failed CI + rework" to "informed decision at edit time."

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#235` (GC-J005: Architecture Decision Impact Surfacing)
