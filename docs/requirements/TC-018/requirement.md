---
id: TC-018
title: "Defect/Bug Linking"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-22T06:14:12.568144Z
updated_at: 2026-03-22T06:14:12.568144Z
---

# TC-018 — Defect/Bug Linking

## Statement

The system shall support creating defects directly from failed test steps with automatic pre-population of bug details from test context. The system shall support bidirectional linking between test executions and defects, viewing defect status from within test management, and integration with external bug trackers (GitHub Issues at minimum).

## Rationale

All best-of-breed tools support defect creation from failed tests. TestRail, Azure Test Plans, Zephyr Scale, Xray, PractiTest, and qTest all auto-populate bug details from test context. Ground Control already has GitHub issue integration to build on.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#697` (TC-018: Defect/Bug Linking)
