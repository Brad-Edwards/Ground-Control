# Delete Implement Telemetry Summarizer Preflight

Issue #1507 removes the orphaned local `/implement` telemetry summarizer after
issue #1500 retired the backend consumer and this repo disabled telemetry. This
note is architecture preflight guidance only. It does not delete the script,
rewrite tests, or change runtime behavior.

## Architecture Boundary

- Treat the removed artifact as the local JSONL summary product:
  `tools/summarize_implement_telemetry.py`, the retired
  `make implement-cost-summary` target, and docs/tests that still advertise
  `.gc/telemetry/*.jsonl` summaries as supported.
- Keep ADR-036 routing metadata alive. `routing.enabled`, route stage names,
  tiers, provider/model validation, and `gc_resolve_workflow_route` are
  separate from the deleted summary product.
- Keep the durable ADR-036 step-observation path separate from the deleted
  local summary path. `gc_log_step_telemetry` now maps step observations onto
  the ADR-061 `WorkflowRun` / `WorkflowPhaseEvent` projection through
  `buildStepObservationEvent`; it does not write local JSONL.
- Do not restore a measurement product just to preserve an old artifact. A
  future summary surface needs a concrete consumer and should consume the
  durable ADR-061 projection, not revive per-clone `.gc/telemetry` scanning.

## Cross-Cutting Concerns

- **Config validation:** keep using `.ground-control.yaml` through
  `gc_get_repo_ground_control_context` / `parseGroundControlYaml`. The
  `telemetry.enabled` shape stays a strict boolean-only map and currently
  remains `false` in this repo.
- **Routing validation:** preserve `DEFAULT_IMPLEMENT_ROUTING_STAGES`,
  `ROUTING_TIERS`, `ROUTING_PROVIDERS`, canonical Claude model ids, and
  `routing.stages.<stage>` strict validation. Removing the summarizer must not
  loosen or remove optional routing metadata.
- **Telemetry validation:** preserve `runLogStepTelemetry`'s structured
  argument checks, `telemetry_disabled` / `telemetry_config_invalid` envelopes,
  fail-open durable-write behavior, and bounded error leakage.
- **Persistence:** local `.gc/telemetry/*.jsonl` files are historical only.
  The live persistence seam is the ADR-061 workflow-run projection, and no
  fallback local writer should be added.
- **Policy and tests:** stale tests should stop asserting support for the local
  JSONL record/path/summarizer contract unless the tested helper has a live
  caller. Tests for routing config and durable step observations remain
  relevant and should not be deleted as collateral.

## Security Layers

- **OS exposure:** deleting the standalone Python CLI removes one subprocess
  path. Do not add a replacement CLI, shell pipe, `gh`, `git`, or `curl`
  invocation for telemetry summaries in this issue.
- **Config shape:** do not add `telemetry.log_dir`, summary path, pricing, or
  backend-url knobs. Unknown telemetry/routing keys should remain rejected by
  the existing config parser.
- **Error envelopes:** live telemetry failures continue to return bounded MCP
  envelopes, not stack traces, raw backend errors, token counts from prompts,
  or filesystem paths supplied by a branch name.
- **Secret handling:** telemetry and docs must not start carrying prompts,
  command transcripts, tokens, environment values, or raw issue/PR bodies.

## Extensibility

The forward seam for run economics is queryable workflow-run data with the
existing `ADR036_STEP_JSONL` emitter, `stage`, `attempt`, tier/model fields, and
bounded outcome fields. If a future caller needs summaries, add that consumer
against the durable projection and make the consumer explicit before adding new
summary tooling.

## Gotchas

- Historical changelog or ADR amendment text may describe what used to exist;
  current workflow docs and active tests must not present the summarizer as a
  supported target.
- `telemetry.enabled: false` is not an instruction to remove the telemetry
  schema validator. It is the opt-in gate for the live durable telemetry tool.
- `.gitignore` may still ignore `.gc/telemetry/` for old local artifacts; that
  is not a product contract by itself.
- The string `ADR036_STEP_JSONL` is an emitter identity retained for continuity;
  its name does not mean the current implementation writes JSONL files.

## Non-Goals

- No new telemetry feature, cost model, pricing table, dashboard, backend
  endpoint, import path, or local-file fallback.
- No change to routing semantics, tier-to-model mapping, or workflow stage
  names.
- No change to issue-thread durable records, review-cycle counters, PR-body
  rendering, or Ground Control requirement file handling.
