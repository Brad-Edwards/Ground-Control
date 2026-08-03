---
id: GC-H008
title: "Threat Model Review Lifecycle"
status: DEPRECATED
type: FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-14T19:32:52.474134Z
updated_at: 2026-07-12T23:08:15.210497Z
---

# GC-H008 — Threat Model Review Lifecycle

## Statement

The system shall support a threat model lifecycle state machine: draft, under-review, approved, stale. Staleness shall be triggered when linked topology, operational assets, code, configuration, requirements, or materially relevant observations or evidence change. Review assignments shall support approval gates with designated reviewers.

## Rationale

Threat models are living graph views over an evolving system. An approved threat model that no longer matches the operational asset and topology context provides false confidence.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#268` (GC-H008: Threat Model Review Lifecycle)
