---
id: GC-O001
title: "Self-Managed Requirements"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 1
created_at: 2026-03-13T23:15:09.568582Z
updated_at: 2026-03-15T18:23:59.996325Z
---

# GC-O001 — Self-Managed Requirements

## Statement

Ground Control shall manage its own requirements within Ground Control, serving as the primary dogfooding use case and ensuring the system is capable of managing a real-world requirement set.

## Rationale

A requirements management system that cannot manage its own requirements lacks credibility. Dogfooding exposes usability issues, missing features, and workflow gaps that external testing alone cannot reveal.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP server — enables self-management of GC requirements)
- IMPLEMENTS → GITHUB_ISSUE `#303` (GC-O001: Self-Managed Requirements)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `.claude/skills/implement` (/implement skill — orchestrates requirement workflow using GC tools)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementsE2EIntegrationTest.java` (E2E integration tests — verify requirement CRUD that enables self-management)
