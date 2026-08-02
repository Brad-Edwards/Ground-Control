---
id: GC-O010
title: "Documentation Coverage Workflow Gate"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-05-23T16:05:32.598394Z
updated_at: 2026-05-23T16:05:37.361644Z
---

# GC-O010 — Documentation Coverage Workflow Gate

## Statement

The system's agentic development workflow shall enforce a documentation coverage gate before completion for diffs that touch workflow behavior, MCP tool surfaces, config parsing, policy, ADRs, public APIs, or user-visible behavior.

The gate shall: (a) classify each changed path into one of a closed surface vocabulary (workflow / mcp_tool / config_parser / policy / adr / public_api / user_visible / doc / unclassified) with a deterministic map from surface class to required documentation target; (b) require the PR body and final report to declare a structured documentation outcome from a closed enum (updated, verified_unchanged, not_updated_authorized) — the last value requiring a bounded rationale string with explicit user authorization; (c) be implemented as executable enforcement at the MCP/policy layer (gc_documentation_coverage MCP tool + run_documentation_coverage_check in tools/policy/checks.py), not as skill prose only; (d) be reflected in the PR body and final-report renderers as a structured `## Documentation` section, with renderer-level validation rejecting incomplete outcomes when a classified surface was touched.

The gate shall also include a prose-style linting layer (Vale, errata-ai/Google package as starting point) installed deterministically into the repo's tooling and run by `make policy` against the documentation paths touched by each diff. Style migration shall be organic: untouched documentation is not rewritten by the gate.

## Rationale

Documentation drift between executable code/config surfaces and the prose that describes them is a recurring failure mode in repos that grow faster than their docs. Issue #891 surfaced the drift concretely: skill prose described a `workflow.pr_title` config knob that `parseGroundControlYaml` did not accept, and the broader `.ground-control.yaml` config contract was only partially documented. ADR-029 places the durable workflow record on the GitHub issue thread and ADR-036 places deterministic rendering in MCP tools, so a documentation gate has to land in the same layer to be enforceable — skill prose alone is reviewable, not gating. The closed surface vocabulary keeps the classifier auditable and extensible; the closed outcome enum keeps the record machine-readable; the Vale layer enforces a separate concern (how docs read) that mirrors the same organic-migration model as the coverage check (no bulk rewrites). This requirement extends the GC-O007 gated agentic development loop with a new Phase-B gate; it does not supersede GC-O007.

## Traceability

- IMPLEMENTS → ADR `ADR-054` (ADR-054: Documentation coverage workflow gate)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (classifyChangedSurface + validateDocumentationOutcome + documentation_outcome render extensions + workflow.pr_title parser + buildSuggestedGroundControlYaml coverage)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_documentation_coverage MCP tool registration)
- IMPLEMENTS → POLICY `tools/policy/checks.py` (run_documentation_coverage_check (codes doc-coverage-outcome-missing, doc-coverage-fixture-error))
- IMPLEMENTS → CODE_FILE `tools/documentation_coverage_fixture.mjs` (Node bridge between tools/policy/checks.py and gc_documentation_coverage classifier)
- IMPLEMENTS → CODE_FILE `tools/install-vale.sh` (Vale v3.14.2 pinned-binary installer (SHA-256 verified; multi-tool fallback; auto vale sync))
- IMPLEMENTS → CONFIG `.vale.ini` (Vale config: StylesPath / MinAlertLevel=error / BasedOnStyles=Google)
- IMPLEMENTS → DOCUMENTATION `docs/DOC_STYLE.md` (Documentation style rule: Google + Diátaxis anchors; normative)
- IMPLEMENTS → GITHUB_ISSUE `896` (Enforce documentation coverage + style as an explicit workflow step)
- IMPLEMENTS → PULL_REQUEST `974` (Add documentation coverage gate and Vale style linter)
- IMPLEMENTS → CODE_FILE `tools/vale-lint-hook.sh` (pre-commit Vale wrapper; installs Vale on first need (PR #980, refined in #984))
- IMPLEMENTS → DOCUMENTATION `skills/implement/steps/step-06-completion-gate.md` (/implement Step 6 — Vale prose lint sub-step (ADR-054); install-on-need pointer added in PR #984)
- IMPLEMENTS → CONFIG `Makefile` (vale-lint + vale-install targets; policy depends on vale-lint (PR #980))
- IMPLEMENTS → CONFIG `.github/workflows/ci.yml` (CI policy job: Vale prose lint step runs make vale-lint BASE_REF=origin/<base> on PRs (PR #980))
- IMPLEMENTS → CONFIG `.pre-commit-config.yaml` (Vale prose lint pre-commit hook via tools/vale-lint-hook.sh (PR #980))
- IMPLEMENTS → GITHUB_ISSUE `979` (Vale runs in make policy but not in pre-commit or CI — close the gap)
- IMPLEMENTS → PULL_REQUEST `980` (Wire Vale prose linter into pre-commit and CI (closes #979))
- IMPLEMENTS → PULL_REQUEST `984` (Vale hook installs on first need; scope rule folded into ADR-054 + DOC_STYLE.md)
- IMPLEMENTS → CONFIG `.vale/styles/GoogleProject/EmDashDensity.yml` (GoogleProject/EmDashDensity Vale rule — house-style override capping em-dash density at 1 per paragraph (ADR-054 § house-style overrides))
- TESTS → TEST `tools/tests/test_policy_doc_coverage_anchors.py` (Tests for run_documentation_coverage_check (positive, negative, docs-only, graceful-skip))
- TESTS → TEST `mcp/ground-control/lib.validateprbodyinput-documentation-outcome.test.js` (validateDocumentationOutcome / documentation_outcome PR-body renderer tests)
