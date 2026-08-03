---
id: TC-021
title: "Test Automation Result Import"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 3
created_at: 2026-03-22T06:14:27.609254Z
updated_at: 2026-03-22T06:14:27.609254Z
---

# TC-021 — Test Automation Result Import

## Statement

The system shall support importing automated test results from standard formats: JUnit XML, NUnit XML, TestNG XML, Robot Framework output, Cucumber JSON, and TAP (Test Anything Protocol). Imported results shall be linked to corresponding test cases and displayed alongside manual results.

## Rationale

All best-of-breed tools support automated result import. Kiwi TCMS supports the widest range of frameworks (pytest, Django, JUnit, TestNG, Robot Framework, TAP). Unified view of manual and automated results is table stakes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#709` (TC-021: Test Automation Result Import)
