---
id: GC-U004
title: "Audit Scope-Control Mapping"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T19:34:26.783740Z
updated_at: 2026-07-12T23:08:15.210600Z
---

# GC-U004 — Audit Scope-Control Mapping

## Statement

The system shall support mapping between audit scope, in-scope operational assets or boundaries, and controls to be tested within that audit. The mapping shall enable queries such as which controls are in scope for this audit, when was this control last audited, and which critical assets or controls have never been audited.

## Rationale

Audit scope planning requires knowing both which controls to test and which operational context they apply to. Historical audit coverage analysis should be able to pivot by asset as well as by control.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#278` (GC-U004: Audit Scope-Control Mapping)
