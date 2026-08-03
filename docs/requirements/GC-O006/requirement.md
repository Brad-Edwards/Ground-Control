---
id: GC-O006
title: "Specification-Driven Development Workflow Tracking"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T18:38:00.451717Z
updated_at: 2026-03-14T18:38:00.451717Z
---

# GC-O006 — Specification-Driven Development Workflow Tracking

## Statement

The system shall track the SDD workflow state per requirement for requirements with target assurance level L1 or above: whether a formal specification exists (SPEC artifact linked via VERIFIES or DOCUMENTS), whether tests exist (TEST artifact linked via TESTS), and whether implementation exists (CODE_FILE artifact linked via IMPLEMENTS). The system shall surface requirements where implementation exists but specification does not, indicating SDD workflow violations.

## Rationale

ADR-012's SDD workflow mandates Classify -> Spec -> Test -> Code -> Verify for L1+ code. GC-O005 enforces requirement-before-code but not spec-before-code. Without workflow tracking, the platform cannot distinguish planned SDD compliance from retroactive documentation. This surfaces the gap between "has a spec" and "has code" for each requirement, enabling process compliance monitoring.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#239` (GC-O006: Specification-Driven Development Workflow Tracking)
