---
id: GC-X107
title: "Per-repository configuration of the Codex review model"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-07-05T17:03:09.330375Z
updated_at: 2026-07-05T17:11:53.424088Z
---

# GC-X107 — Per-repository configuration of the Codex review model

## Statement

The pre-push Codex review (Step 6.5 of the /implement and /quickfix lanes, per GC-O007) shall allow its review model to be configured per repository via a new workflow.codex_review.model field in .ground-control.yaml. When unset, behavior is unchanged: the codex CLI runs with no --model argument and uses its host default. When set, the configured model shall be passed to the codex binary as --model across both the synchronous and asynchronous (gc_codex_job) review paths, and an optional per-call model parameter on gc_codex_review and gc_codex_review_cycle shall override the config value. Unknown model ids shall be rejected at config-normalization time. The change shall preserve the GC-X104 independent-confirmation reviewer's model-family independence and the ADR-027/029 reviewer-of-record invariant (model selection stays inside the MCP boundary). A governing ADR (refining ADR-031) and documentation of the new field are required.

## Rationale

Today the Codex review model is not selectable by Ground Control at all: buildCodexReviewExecArgs (mcp/ground-control/lib.js) emits no --model flag, runSingleCodexReview shells the literal codex binary, the gc_codex_review / gc_codex_review_cycle schemas expose no model param, and normalizeReviewerConfig allow-lists only pre_push_cap, so .ground-control.yaml workflow.codex_review rejects any model key. The effective review model is whatever the codex CLI defaults to on the host — invisible to the repo and unpinnable. Different repos have different review needs (a security-sensitive service may want a stronger model, a high-volume low-risk repo a cheaper one), and pinning the model in version-controlled config makes the review record reproducible across hosts. The Step 6.6 test-quality reviewer already exposes a per-call model (default claude-sonnet-5) on gc_test_quality_review, so this is an established pattern. ADR-031 explicitly anticipates .ground-control.yaml as the escape valve for hard-coded review knobs. Scope is model selection only — distinct from GC-X106 (risk-based step composition) and GC-X101–X105 (in-loop stopping criteria).
