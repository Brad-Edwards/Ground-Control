---
id: GC-J010
title: "ADR-Requirement Bidirectional Impact Analysis"
status: DRAFT
type: FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-14T19:45:58.888914Z
updated_at: 2026-03-14T19:45:58.888914Z
---

# GC-J010 — ADR-Requirement Bidirectional Impact Analysis

## Statement

The system shall support bidirectional impact analysis between ADRs and requirements: (a) given an ADR, show all requirements it satisfies or constrains, (b) given a requirement, show all ADRs that inform its design. When an ADR transitions to deprecated or superseded, the system shall flag affected requirements for review. Agent-queryable for automated architecture-requirement alignment assessment.

## Rationale

J001 establishes ADR-requirement linking, but linking without impact analysis is a data model without a use case. Impact analysis answers: "if we reverse this architecture decision, which requirements are affected?" and "is this requirement still valid given the current set of active ADRs?" Deprecation-triggered flagging prevents requirements from silently depending on abandoned decisions.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#288` (GC-J010: ADR-Requirement Bidirectional Impact Analysis)
