---
id: GC-GRC-016
title: "On-Demand Arbitrary-Scope Assessment Lane"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:26:41.586381Z
updated_at: 2026-07-11T23:43:44.555594Z
---

# GC-GRC-016 — On-Demand Arbitrary-Scope Assessment Lane

## Statement

The system shall provide a standalone GRC assessment lane runnable outside the implementation workflow, on demand.

(a) Scope selection shall support: whole project, package/path sets, boundaries, assets, named risk/threat sets, and the current stale/drift set. Scope is user-directed.

(b) Modes shall include: model (build or extend the baseline for the scope), reassess (re-derive, re-enumerate, re-score existing entities and surface deltas), and re-screen (re-check coverage against current rule packs and control packs).

(c) Arbitrarily large scopes shall partition (by boundary/package/asset) and execute in parallel, with deterministic merge, deduplication, and graph reconciliation of results.

(d) Lane outputs shall pass a review stage before committing to the graph; the human gate is configurable per project.

(e) The lane shall share the derivation/enumeration engine with the in-loop gates — one engine, two entry points.

## Rationale

Continuous in-loop coverage handles change-sized scopes; the lane handles everything else — bootstrap, periodic reassessment, post-incident sweeps, framework upgrades — under user direction. Sharing one engine guarantees the lane and the loop can never disagree about what the model says.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1129` (Issue #1129: GC-GRC-016 on-demand arbitrary-scope assessment lane (gc_grc_assess))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcassessment/service/GrcAssessmentRunService.java` (GrcAssessmentRunService durable assessment lane orchestration)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-grc-assess.js` (gc_grc_assess MCP adapter)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcassessment/GrcAssessmentRunServiceTest.java` (GrcAssessmentRunService behavior tests)
- TESTS → TEST `mcp/ground-control/gc-grc-assess.test.js` (gc_grc_assess MCP adapter tests)
- DOCUMENTS → DOCUMENTATION `docs/API.md#grc-assessment-runs-gc-grc-016` (GRC assessment runs API reference)
- DOCUMENTS → DOCUMENTATION `skills/assess/SKILL.md` (Agent skill for the on-demand GRC assessment lane)
