---
id: GC-O012
title: "Per-Run GRC Screening Gate"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-06-10T15:27:15.897803Z
updated_at: 2026-07-11T23:43:44.555613Z
---

# GC-O012 — Per-Run GRC Screening Gate

## Statement

Every /implement run shall screen its change surface against the project's threat models, risk scenarios, and controls before planning completes, and shall record the outcome as a durable, deterministic screening record on the GitHub issue thread.

(a) Verdict. The screening shall produce exactly one verdict per run from {security_relevant, not_security_relevant, no_baseline}. not_security_relevant verdicts shall carry a one-line rationale. no_baseline shall be recorded when the project has no threat-model baseline; the declination shall be explicit and detectable, never a silent skip.

(b) Security-relevant obligations. security_relevant runs shall create or update the affected threat-model entries and shall link the relevant risk scenarios and controls to the changed code artifacts (control links with targetType CODE; traceability links where the artifact is requirement-bound) before the run's completion gate.

(c) Durable record. The screening record shall be posted by a deterministic renderer MCP tool (ADR-036 family), shall carry a schema-versioned machine-readable marker, and shall enumerate the UIDs of every entity created, updated, or confirmed — sufficient for a server-side assertion to verify the claimed links without trusting the agent's claims (companion assertion: gc_assert_grc_reconciled).

(d) Workflow integration. The screening step shall be part of the canonical /implement step list with a routed stage id, and the step semantics and record format shall be documented in the development workflow reference.

## Rationale

GRC content authored out-of-band decays; enforcement that lives only in workflow prose gets short-circuited (June-6 redesign revert, #1090). Embedding screening in every run keeps the threat and risk picture contextually grounded in what actually changed, and the schema-versioned durable record gives the data layer a verifiable artifact that server-side assertions (issue #1100) can enforce — gates with teeth live in the server, not the prose. Anchors the /implement GRC screening gate additions (issue #1099) for traceability per the structural-gate planning rule.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (GRC screening renderer implementation in lib.js)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (GRC screening tool registration in index.js)
- DOCUMENTS → DOCUMENTATION `docs/DEVELOPMENT_WORKFLOW.md` (Development workflow reference documenting GRC screening step)
- DOCUMENTS → ADR `architecture/adrs/057-per-run-grc-screening-gate.md` (ADR-057: Per-run GRC screening gate design decision)
- IMPLEMENTS → GITHUB_ISSUE `1099` (Issue #1099: /implement Step 3.5 GRC screening gate)
- IMPLEMENTS → PULL_REQUEST `https://github.com/Brad-Edwards/Ground-Control/pull/1109` (PR #1109: /implement Step 3.5 GRC screening gate)
- IMPLEMENTS → GITHUB_ISSUE `1100` (Issue #1100: gc_assert_grc_reconciled server-side GRC completion gate)
- IMPLEMENTS → PULL_REQUEST `https://github.com/Brad-Edwards/Ground-Control/pull/1111` (PR #1111: gc_assert_grc_reconciled GRC completion gate)
- IMPLEMENTS → DOCUMENTATION `skills/implement/steps/step-17-completion.md` (Step 17 workflow wiring of gc_assert_grc_reconciled via gc_assert_completion (#1103))

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → DOCUMENTATION `skills/implement/steps/step-03.5-grc-screening.md` (GRC screening step prose (step 3.5))
- TESTS → TEST `mcp/ground-control/gc-grc-screening.test.js` (GRC screening unit tests (53 tests))
- TESTS → TEST `mcp/ground-control/gc-grc-reconciled.test.js` (gc_assert_grc_reconciled unit tests (verify/missing/no_baseline/cross-type/no-override paths))
