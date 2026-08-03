---
id: GC-X025
title: "Lint runs on a cadence independent of scheduled processing"
status: DRAFT
type: NON_FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-04-12T19:15:46.160554Z
updated_at: 2026-04-12T19:15:46.160554Z
---

# GC-X025 — Lint runs on a cadence independent of scheduled processing

## Statement

The health check (lint) operation shall run on its own cadence, independently of the scheduled processing cadence, so that lint frequency can be tuned without affecting the speed or cost of regular ingest.

## Rationale

Lint is more expensive per run than incremental ingest because it reads the whole knowledge base. It also needs to run much less often to be effective. Coupling the two cadences would force a bad tradeoff: either lint runs every sweep (wasteful) or sweep runs every lint (too slow for real-time value). Independent cadences let each operation run at the frequency that matches its cost and value.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#527` (Knowledge system 6/6: knowledge base lint pass)
