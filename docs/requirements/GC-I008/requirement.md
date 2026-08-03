---
id: GC-I008
title: "Regulatory Change Entity"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T16:56:51.265724Z
updated_at: 2026-07-12T23:08:15.210295Z
---

# GC-I008 — Regulatory Change Entity

## Statement

The system shall support a regulatory change entity with fields: source authority, change description, affected frameworks, effective date, impact severity (breaking, significant, minor, informational), status (identified, assessing, implementing, closed), and linked controls requiring update.

## Rationale

Compliance frameworks are not static — SOC 2 criteria evolve, NIST CSF has major version updates, new regulations emerge (DORA, NIS2). Without tracking regulatory changes, organizations discover framework updates during audits rather than proactively adapting controls.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#223` (GC-I008: Regulatory Change Entity)
