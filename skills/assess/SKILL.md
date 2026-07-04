---
name: assess
description: "Run the on-demand GRC assessment lane over a user-directed scope. Creates a durable gc_grc_assess run, enforces review before commit unless project policy disables it, and records bounded graph-effect ids/counts through Ground Control."
argument-hint: "<mode> <scope> [values...]"
disable-model-invocation: true
---

# Assess: On-Demand GRC Assessment Lane

Use `/assess` for GC-GRC-016 baseline bootstrap, reassessment, post-incident sweeps, framework upgrades, and stale/drift review. This is a second entry point into the ADR-058 engine, not a second GRC engine.

## Modes

- `model`: build or extend the baseline by deriving facts and architecture-model graph effects for the selected scope.
- `reassess`: re-derive and re-enumerate an existing modeled scope; review deltas before commit.
- `re_screen`: re-check coverage against current/pinned packs without running derivation.

## Scope

Valid scope selectors map directly to `gc_grc_assess.scope_type`:

- `whole_project`
- `package_path_set`
- `boundary`
- `asset`
- `named_threat_set`
- `named_risk_set`
- `stale_drift_set`

For `boundary`, pass declared boundaries from `.ground-control.yaml` when available so approved partition commits can resolve boundary keys to path selectors.

## Workflow

1. Resolve project context with `gc_get_repo_ground_control_context`.
2. Choose `mode`, `scope_type`, and `scope_values` from the user request. Do not invent requirement prefixes or graph entities.
3. Call `gc_grc_assess` with `action=run`, `review_decision=request_review`, and an `idempotency_key` stable for the requested project/mode/scope/commit. Include `commit_sha`, `languages`, and `surfaces` for `model` and `reassess`.
4. Present the returned run id, partition counts, dedup summary, and proposed next action. Do not commit graph effects from a preview run.
5. If review approves, call `gc_grc_assess` with `action=review`, `id=<run id>`, and `review_decision=approved`. If review rejects, call the same action with `review_decision=rejected`.
6. Report the committed run id and bounded `graph_effects` ids/counts. Do not copy raw source, scanner output, prompts, secrets, or raw evidence into the report.

## Guardrails

- Use `gc_grc_assess`; do not shell out, run local scanners, expose Cypher/SQL, or use generic REST passthrough for lane writes.
- Review-before-commit is a backend/MCP gate. Do not replace it with prose.
- Candidate threats and controls remain proposals until reviewed and committed through existing Ground Control write paths.
- Re-runs should pass the same `idempotency_key` so Ground Control returns the existing durable record instead of executing the same lane again.
