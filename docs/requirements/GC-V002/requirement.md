---
id: GC-V002
title: "Finding Source Linking"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T19:34:43.910807Z
updated_at: 2026-07-12T23:08:15.210617Z
---

# GC-V002 — Finding Source Linking

## Statement

The system shall support linking findings to their originating source, such as audit test results, control test results, policy violation reports, vulnerability scans, exposure observations, or other evidence-backed evaluations. Each finding shall have traceable provenance showing what process identified the deficiency, which operational assets were in scope, and what evidence supports it.

## Rationale

A finding without provenance is an unsubstantiated claim. Source linking ensures every finding is evidence-backed, traceable to the evaluation that identified it, and grounded in the operational scope that was examined.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#280` (GC-V002: Finding Source Linking)
