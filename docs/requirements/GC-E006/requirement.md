---
id: GC-E006
title: "Test-Requirement UID Convention"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T01:24:26.193264Z
updated_at: 2026-03-14T01:24:26.193264Z
---

# GC-E006 — Test-Requirement UID Convention

## Statement

The system shall support deriving TESTS traceability links by scanning test artifacts for requirement UID references (for example, in test names, annotations, or comments matching GC-xxxx patterns), enabling automated discovery of test-to-requirement mappings from the codebase itself.

## Rationale

Manually creating TESTS links is error-prone and easily forgotten. If tests reference requirement UIDs by convention (for example, @TracesTo("GC-A001") or test method names containing the UID), the traceability graph can be populated automatically from the codebase, making test coverage derivable rather than maintained.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#702` (GC-E006: Test-Requirement UID Convention)
