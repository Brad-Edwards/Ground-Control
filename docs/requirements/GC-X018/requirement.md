---
id: GC-X018
title: "Manual invocation of knowledge base processing"
status: DRAFT
type: INTERFACE
priority: SHOULD
wave: 6
created_at: 2026-04-12T19:15:01.598375Z
updated_at: 2026-04-12T19:15:01.598375Z
---

# GC-X018 — Manual invocation of knowledge base processing

## Statement

The system shall allow an operator to manually invoke knowledge base processing for a single registered repository or for all registered repositories, producing the same effect as a scheduled run of the same scope.

## Rationale

Scheduled processing is not always soon enough. Operators need to trigger processing immediately when debugging, when a particularly important PR has just merged, or when they want to verify a configuration change. A manual trigger that shares the same engine as the scheduler keeps behavior consistent across trigger modes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#525` (Knowledge system 4/6: admin CLI and scheduler lifecycle)
