# ADR-036: Per-Step Model Routing, Durable-Record Tool Surfaces, and Step Telemetry

## Status

Accepted

## Date

2026-05-11

## Context

ADR-021 ("Gated Agentic Development Loop") and ADR-029 ("Issue-Thread Gate Model")
codify GC-O007 — the four-phase `/implement` workflow with one human touchpoint
(PR merge), one Codex review pass (pre-push, hard-capped at three cycles), and
the GitHub issue thread as the durable record. The workflow contract is sound;
the cost profile of running it is not.

Issue #867 (and its scoped sibling #868) measured the within-run token cost
surface and identified three structural changes that reduce cost without
weakening any gate:

1. **Per-step model routing.** Today every step in a `/implement` run executes
   on the parent session's model — typically Opus-class. Most steps don't need
   Opus-class reasoning: polling CI, parsing preflight output, drafting
   structured comments, and applying a fix that codex already designed are
   firmly in haiku/sonnet-tier territory. Without explicit routing, the entire
   run amortizes at the parent's price.
2. **Tool surfaces for durable records.** Step 6.5's decision records, Step
   19's final reports, and Step 9's PR body are templated long-form comments
   produced once per cycle / per run. They are agent-authored free prose
   today: large in tokens, easy to drift from the policy gates they must
   satisfy (`check_pr_body`, ADR-029 zero-deferral, traceability markers).
   Replacing them with deterministic MCP tools that take structured input
   collapses the prose cost to ~zero and lifts gate compliance into the tool
   layer.
3. **Per-step telemetry.** Without per-step measurement, every "this saved
   tokens" claim is unverifiable and every routing decision is a guess. A
   small append-only JSONL log per run, plus a summarizer, makes cost a
   first-class operational signal.

The three changes ride the same architectural seam: deterministic tool
surfaces. Each new MCP tool is structured-input-in, canonical-output-out, no
LLM call, no workflow state. They become Temporal activities directly under
GC-O009 with no shape change.

The gate model is preserved end to end: one human touchpoint (PR merge), no
plan-approval gate, no post-push Codex review, ADR-029's configurable
pre-push cap (default 1 cycle per issue #906; per-repo override via
`.ground-control.yaml::workflow.codex_review.pre_push_cap`, bounds `[1, 10]`),
zero deferral. This ADR amends ADR-021 with cost-side machinery; it does
not redefine GC-O007's gate contract.

## Decision

### Provider-neutral routing seam

The `/implement` SKILL declares a stable **workflow step id** plus a
**capability tier** for each step. The tier is provider-neutral:

| Tier | Intended capability | Claude Code mapping |
|------|---------------------|---------------------|
| `low` | Mechanical action, polling, gh wrapping, file reads | `claude-haiku-4-5` |
| `medium` | Bounded reading + applying a designed decision; structured drafting | `claude-sonnet-4-6` |
| `high` | Architectural reasoning, novel-fork interpretation, first-cycle review consume | `claude-opus-4-7` (the parent) |

Drivers map tier to a concrete model. Claude Code drivers spawn an `Agent`
subagent with the corresponding model for routed steps. Codex drivers have no
equivalent surface today; they ignore the tier annotation and run all steps
on the session model. The architecture is forward-compatible — a future
Codex-side router consumes the same step-id+tier contract without further ADR
work.

Routing is opt-in per repo via `.ground-control.yaml`'s `routing.enabled`
knob (default `false`). Existing repos see no behavior change until they flip
the knob. The executable routing contract is stage/purpose based: callers ask
`gc_resolve_workflow_route` for a stage such as `implementation`,
`test_quality_review`, or `final_report`, and the tool returns the configured
provider, agent, canonical model id, tier, and fallback policy. The skill keeps
the step matrix legible, but the resolver is the boundary that prevents silent
fallback from being mistaken for routed execution.

The routing seam is calibrated so subagent context-establishment overhead does
not exceed the savings. Very short steps (sub-second polling, single-call
helpers) only get routed to `low` tier when their context cost is also
bounded; otherwise the parent absorbs them.

### Durable-record tool surfaces

Three new MCP tools replace agent-authored long-form comments:

- **`gc_post_decision_record(issue_number, cycle, reviewer, findings[])`** —
  renders the canonical Step 6.5 decision-record Markdown from structured
  input, filters secrets via `detectSensitiveBodyContent`, posts to the
  issue thread with a `gc:decision-record` marker, returns `{ ok, comment_url,
  comment_id, finding_count }`. Rejects `decision: "defer"` server-side
  (defense in depth on top of the `block-defer-language.py` PreToolUse hook).
- **`gc_post_final_report(issue_number, pr_number, ...)`** — same pattern for
  Step 19. Structured input (in-scope requirements, files by change kind,
  reviews per reviewer, traceability reconciliation, CI/SonarCloud status) →
  canonical Markdown → issue thread → `gc:final-report` marker.
- **`gc_render_pr_body(issue_number, change_class, ...)`** — renders a PR
  body that satisfies `check_pr_body`'s policy gates (template sections,
  requirement UIDs, ADR impact, three Ground Control Checks, IMPLEMENTS/TESTS
  markers, no defer language). Returns the body string for the caller to
  pass to `gh pr create --body`. `change_class ∈ {doc-only, source,
  source+migration}` shapes the integration-tests / changelog-fragment cells.
  Renderer is decoupled from `check_pr_body`; a Python test in
  `tools/tests/test_policy.py` asserts the rendered output passes the policy
  predicate, so drift breaks a test.

All three tools share the same boundary: structured input → pure renderer →
sensitive-content filter → GitHub post (or string return for the PR body) →
structured envelope. They reuse `ensureGitRepo`, `getOwnerRepo`,
`detectSensitiveBodyContent`, and the existing `gh api` argv-style execution
path; no new GitHub client, no new marker family beyond the two listed above.

The SKILL stops `gh issue comment`-ing decision records and final reports
once these tools land. Step 9 calls `gc_render_pr_body` and uses the returned
body; **per issue #901, Step 9 also validates the PR *title* locally against
two stable conventional-commit rules before `gh pr create` — single
`<type>(<optional-scope>): <subject>` (no compound `security/docs:` prefixes)
and a lowercase-leading subject (`^[a-z].*$`, uppercase acronyms reshaped).
The body renderer and the title validator are independent concerns living in
the same Step 9 — the renderer is an MCP tool, the title rule is a local
predicate the agent re-applies on every reshape.** Step 6.5 calls `gc_post_decision_record` for every cycle; **Step 6.6
calls `gc_test_quality_review`** (per #884 v2 — the prior `Skill("review-tests")`
boundary returned prose findings that the autoregressive parent agent
kept echoing back to the user instead of fixing in-turn, defeating the
SKILL.md prose rule; the MCP tool returns a structured envelope with
`next_action` that the agent reads as a directive). Issue #906 moved this
call pre-push (former Step 13 → new Step 6.6) so the PR opens with both
AI-assisted reviewers clean; the same #906 amendment dropped the default
pre-push cap for both reviewers from 3 to 1, configurable per repo via
`workflow.codex_review.pre_push_cap` and `workflow.test_quality_review.pre_push_cap`.
The MCP tool itself is unchanged — only its workflow placement and default
cap value shifted. After Step 6.6's
cycle the parent calls `gc_post_decision_record` with the
`fix`/`wontfix`/`not-applicable` dispositions (cycle counter, durable
record); a clean cycle is the structured advance-to-Phase-C signal once
that post returns `ok: true` (the string was `..._advance_to_step_14`
before issue #906 collapsed Step 14 into Step 10's existing CI watch;
new MCP envelope returns `..._advance_to_phase_c`). See
`architecture/notes/test-quality-review-engine.md` for the full MCP
tool mechanism (claude CLI exec, `ANTHROPIC_API_KEY` strip / OAuth,
cycle markers, failure modes). Step 19 calls `gc_post_final_report`.

### Telemetry contract

Operational measurement only — **not** workflow state, not a cycle counter,
not compliance evidence. The issue thread and Ground Control traceability
remain the audit record.

Each routed step writes one JSONL line via `gc_log_step_telemetry` to
`.gc/telemetry/<issue>-<sanitized-branch>.jsonl`:

```json
{
  "schema": "gc.implement.telemetry/v1",
  "ts": "2026-05-11T07:00:00Z",
  "issue": 868,
  "branch": "868-route-tools-telem",
  "step": "4.5",
  "tier": "medium",
  "model": "sonnet",
  "wall_time_ms": 12480,
  "input_tokens": 8421,
  "output_tokens": 612,
  "outcome": "ok"
}
```

- `wall_time_ms` is mandatory; the agent measures around its delegation calls.
- `input_tokens` and `output_tokens` are optional — Claude Code's `Agent` tool
  does not surface per-call counts today, so the writer accepts `null`. When
  token counts are absent, the summarizer reports wall time and the per-step /
  per-model call counts; dollar-cost translation is **not** in v1's scope and
  is explicitly future work (a per-model price table goes stale faster than the
  workflow surface; shipping cost estimation without a maintained price source
  is worse than shipping wall time alone). The contract is "measure what we
  can measure reliably."
- The `branch` field in every record AND the branch-derived path segment are
  both sanitized: any character outside `[A-Za-z0-9._-]` becomes `_`, and the
  result is truncated to 60 characters. Empty / pathological inputs become
  `unknown`. The record stores the sanitized form (not the raw input) so the
  filename and the record cannot disagree; the original branch identity is
  always carried via the issue number, which is canonical anyway. Path is
  repo-relative, validated via `resolveRepoRelativePath` +
  `assertRealpathInRepo` against the canonicalized repo root so symlinks can
  never let the writer escape `.gc/telemetry/`.
- Telemetry is opt-in per repo via `.ground-control.yaml`'s
  `telemetry.enabled` knob (default `false`).
- `.gc/telemetry/` is gitignored. The summarizer (`make implement-cost-summary`)
  aggregates per-step and per-model totals.

### Forward compatibility with GC-O009

Every tool surface in this ADR is deterministic, side-effect-bounded, takes
structured input, and returns a stable JSON envelope. Specifically:

- No tool reads or writes Temporal-incompatible state.
- No tool blocks on the agent's chat-style stream.
- Telemetry path is local-file; the contract maps cleanly to Temporal's
  built-in visibility when the workflow moves to Temporal.
- The routing tier abstraction is provider-neutral, so GC-O009's
  "(f) Configurable LLM provider" clause inherits it without re-litigation.

When GC-O009 lands, the four new tools become Temporal activities directly.
This ADR does NOT implement Temporal, introduce a workflow engine, durable
queue, or worker code. It only ensures the bridge surface is Temporal-shaped.

### Replaces / amends

- **ADR-021** is amended (gain a new amendment blockquote citing this ADR).
  The gate model and phase structure are unchanged; only the cost-side
  machinery changes.
- **ADR-029** is amended by issue #906 only to make the Codex cap
  configurable per repo (default 1, override via
  `workflow.codex_review.pre_push_cap`); the zero-deferral rule,
  issue-thread-as-durable-record contract, and one-human-touchpoint contract
  all stand.
- **ADR-027** is unchanged; the agent-neutral packaging seam absorbs the new
  tools as additions, not redefinitions.
- **ADR-028** is unchanged; this ADR is the SKILL/MCP-level precursor to its
  Temporal boundary.

The `workflow-guardrail-sync` policy rule
(`architecture/policies/adr-policy.json`) gains this ADR in its `requireAll`
list so future SKILL changes must keep it in sync.

## Consequences

### Positive

- Within-run cost drops by routing the bulk of `/implement` steps to
  haiku/sonnet tiers; opus-class reasoning is preserved where it is needed
  (Step 4 plan writing, Step 6.5 first-cycle review interpretation).
- Decision records, final reports, and PR bodies become deterministic
  artifacts produced by tools, not agent prose. Drift from policy gates is
  caught by a renderer-vs-policy test, not by waiting for CI to complain.
- Per-step telemetry makes cost a measurable, comparable signal across runs
  and across drivers.
- The four new tools are Temporal-shaped; GC-O009 inherits them as activities.

### Negative

- A second config knob in `.ground-control.yaml` (`routing` + `telemetry`)
  and a second ADR for the workflow surface (this one). Both must be kept in
  sync with the SKILL.
- Subagent context-establishment overhead is a fixed per-call cost; if the
  matrix mis-tiers a tiny step the savings can evaporate. Mitigation: the
  matrix is calibrated conservatively, and the telemetry signal will surface
  mis-tiering quickly.
- Per-call token counts depend on the harness exposing them; Claude Code
  does not today, so telemetry's `input_tokens`/`output_tokens` are `null`
  until that lands.

### Risks

- **Telemetry-as-state drift.** If a future agent or sweep treats the
  telemetry log as workflow state (a counter, a gate), it would re-introduce
  exactly the local-file-state-vs-issue-thread divergence ADR-029 closed.
  This ADR makes the operational-only contract explicit; a future tool that
  treats telemetry as gate state must amend this ADR first.
- **Routing degrades Codex driver coverage.** Codex has no `Agent`-with-model
  surface today. The architecture is provider-neutral so Codex routing is
  unblocked, but until a Codex-side router ships, Codex runs all steps on
  the session model. That asymmetry is acceptable for a bridge ADR.
- **PR-body renderer vs policy drift.** If `check_pr_body` adds a new
  required header and the renderer is not updated, the body fails the policy
  gate at CI time. Mitigation: the renderer-vs-policy compose test
  (`tools/tests/test_policy.py`) catches drift on the same PR.

## Implementation references

- `architecture/notes/implement-cost-routing-tool-surfaces-preflight.md` — preflight
  design context for this ADR.
- `mcp/ground-control/lib.js` and `mcp/ground-control/index.js` — the four
  new tool implementations and registrations.
- `mcp/ground-control/lib.test.js` — renderer / validator / containment tests.
- `skills/implement/SKILL.md` — the routing matrix and Step 6.5 / 9 / 19
  wiring.
- `docs/WORKFLOW.md` and `docs/DEVELOPMENT_WORKFLOW.md` — the workflow-side
  documentation of the routing seam, tool boundary, and telemetry contract.
- `tools/policy/checks.py` and `tools/tests/test_policy.py` — the
  renderer-vs-policy compose test and the workflow-guardrail-sync rule that
  pins this ADR to future SKILL edits.
- `Makefile`, `tools/summarize_implement_telemetry.py` — the summarizer
  target.
- `.ground-control.yaml` (this repo) — opts in to `routing.enabled: true`
  and `telemetry.enabled: true` so the change is dogfooded.
- `changelog.d/868.changed.md` — release note fragment.

## Amendments

**2026-05-19 (issue #931).** No change to the routing stage names, tier
semantics, or telemetry record shape. The downstream deterministic tools
(`gc_post_decision_record`, `gc_post_final_report`, `gc_render_pr_body`) gain
optional verdict-envelope fields on `gc_post_decision_record` — `verdict`,
`architectural_read`, `notes[]` — alongside the existing `findings[]` input.
The renderer contract (canonical Markdown, sensitive-content scrub,
reserved-marker rejection, marker family `gc:decision-record`, defer
rejection) is unchanged. See ADR-029 (amendments) for the envelope shape and
issue #931 for the principal-engineer recalibration motivation.

**2026-05-19 (issue #934).** Extends the routing design from "cheap workers
for cheap steps" toward "subagents as context boundaries plus MCP tools as
loop drivers". No change to the routing stage names, the tier-to-model
mapping, the telemetry record shape, or the GC-O007 gate contract. What
changes is the **packaging of the workflow prose** and the **boundary at
which loops execute**.

1. **Thin orchestrator + per-step files.** `skills/implement/SKILL.md` is
   reduced from a monolithic 716-line script to a ~100-line orchestrator
   that enumerates step ids and delegates per-step work to subagents.
   Per-step prose lives at `skills/implement/steps/step-NN-<id>.md`, one
   file per step. The canonical Review loop rules live at
   `skills/implement/steps/_review-loop-rules.md`; Steps 6.5 and 6.6
   reference it by path (the duplicated prose at the bottom of the old
   SKILL.md and across the two step bodies is removed). Per-step files are
   workflow prose packaging only — the executable schema remains
   `.ground-control.yaml` + `gc_get_repo_ground_control_context`; stage
   ids are unchanged.

2. **Subagents as context boundaries.** For every step whose route resolves
   to `agent: subagent`, the parent spawns a subagent whose prompt is
   verbatim "Execute `skills/implement/steps/step-NN-<id>.md` against issue
   N; return `{status, cached_for_next_step}`". The parent never loads the
   step file. The subagent's return envelope is structured — never raw
   `gh`/`git` output, never full file contents, never verbatim review
   prose. The savings target is the parent-orchestrator context, which
   used to carry the full SKILL prose for the entire 1–2 hour run.

3. **MCP tools drive loops, not the agent.** Four new tools land:

   - `gc_codex_review_cycle` — wraps the existing `gc_codex_review` AND
     auto-posts the per-cycle decision record. Returns a compact terminal
     envelope: `{ok, reviewer, cycle, cap, status, next_action,
     findings_summary, findings_record_url, decision_record_url}`.
     Verbatim review prose stays server-side via the underlying findings
     record. Auto-posted decisions are always `decision: "fix"` — the only
     decision the cycle tool can record without user authorization. A
     subagent that has obtained user authorization for a wontfix calls
     `gc_post_decision_record` directly with the override AFTER the cycle.
   - `gc_test_quality_review_cycle` — same shape as the codex wrapper, for
     test-quality reviews. Both cycle wrappers share one parameterized
     internal seam (`_runReviewCycleShared`) parameterized by reviewer and
     cap source; there is exactly one cycle implementation, not one per
     reviewer.
   - `gc_watch_ci_run` — server-side GitHub Actions poller. Replaces the
     per-poll agent turn cost of /implement Step 10. Returns one terminal
     envelope `{conclusion, failed_steps[], log_summary}` after the run
     reaches a terminal state, hits the queued-too-long cap (5 min
     default), or hits the total cap (45 min default). Raw CI logs stay
     server-side; only the bounded UTF-8 tail of `gh run view --log-failed`
     is returned.
   - `gc_watch_sonar_analysis` — server-side SonarCloud poller. Returns
     `{quality_gate, issues_summary, hotspots_summary,
     full_issue_export_path}` after the analysis is fetched and
     paginated. The `SONAR_TOKEN` is read at call time and passed only in
     the Authorization HTTP header — never in argv, telemetry, exports,
     or returned envelopes (the issue #934 preflight binding rule).

4. **Issue-thread cache.** `gc_get_issue_thread` returns the body +
   comments + a sha256 content hash on first call. Subsequent calls with
   the same `expected_hash` return `{unchanged: true}` without re-fetching
   from GitHub. The cache is keyed by `(repoRoot, issueNumber)` —
   explicitly NOT branch-keyed — and is operational only; the GitHub
   issue thread remains the durable record per ADR-029. `expected_hash=null`
   always forces a fresh fetch, used by callers after a posting may have
   failed or when marker state is uncertain.

5. **Telemetry actually writes.** The `gc_log_step_telemetry` tool was
   correctly implemented from #868 but never called by the SKILL prose.
   The new orchestrator calls it at the end of every routed step from
   one place (the per-step harness), producing one JSONL line per
   step in `.gc/telemetry/<issue>-<sanitized-branch>.jsonl`. Schema
   unchanged.

6. **Policy follows the new layout.** The
   `tools/policy/checks.py::run_test_quality_decision_record_contract`
   check, which used to read `skills/implement/SKILL.md` directly, now
   reads `skills/implement/steps/step-06.6-test-quality-review.md` as
   the primary contract source and falls back to SKILL.md for backward
   compatibility. The step heading regex accepts H1 in addition to
   H2–H4 to match the per-step file convention. The Step 6.6 file
   explicitly restates the required contract markers
   (`gc_post_decision_record`, `findings: []`, `ok: true`,
   findings-fix-in-same-turn directive) — the cycle wrapper auto-implements
   them, but the step file documents them for the policy check.

The four new tools are additive — `gc_codex_review`, `gc_test_quality_review`,
`gc_post_decision_record`, and the rest stay unchanged in shape and signature
so direct callers (including `/quickfix` and external scripts) keep working.

The cycle wrappers, watch tools, and issue-thread cache are the substrate
GC-O009 (Workflow Orchestration via Temporal) will eventually formalize as
Temporal **activities** with typed inputs/outputs. This work is bridge work
toward GC-O009 — moving repeated loops into MCP boundaries that return one
terminal envelope per invocation, instead of having the agent drive each
iteration. No Temporal adoption, no DB tables, no branch-keyed counters, no
new workflow DSL; the issue thread on GitHub remains the durable record.
