---
id: GC-E005
title: "Artifact Change Detection"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-13T23:13:01.531467Z
updated_at: 2026-03-14T01:10:02.626987Z
---

# GC-E005 — Artifact Change Detection

## Statement

The system shall detect when a linked artifact (file, test, proof) has changed since its traceability link was last validated, and mark the link as stale for re-verification.

## Rationale

Code changes can invalidate verification evidence. If a file implementing a requirement changes, the proof or test covering that requirement may need re-execution. Change detection triggers re-verification workflows.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#681` (GC-E005: Artifact Change Detection)
