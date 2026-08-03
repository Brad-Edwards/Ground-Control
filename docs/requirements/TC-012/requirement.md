---
id: TC-012
title: "Shared Steps / Reusable Steps"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-22T06:13:43.517902Z
updated_at: 2026-03-22T06:13:43.517902Z
---

# TC-012 — Shared Steps / Reusable Steps

## Statement

The system shall support shared steps as independent reusable entities that can be referenced by multiple test cases. Changes to a shared step shall automatically propagate to all referencing test cases. Test cases shall support calling other test cases as nested steps.

## Rationale

Azure Test Plans, TestRail, Zephyr Scale, PractiTest, and Xray all support shared/reusable steps. Reduces duplication and ensures consistency when common procedures change.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#691` (TC-012: Shared Steps / Reusable Steps)
