# Implement Thin Orchestrator and Server-Side Loops Preflight

Issue #934 reduces `/implement` parent-context cost by moving workflow prose to
per-step files, moving repeated loops into MCP tools, caching issue-thread
reads, summarizing raw CI/Sonar/review payloads, and making telemetry useful.
This note is architecture preflight guidance only; it does not implement the
workflow refactor or any MCP tool.

## Architectural Frame

- Preserve GC-O007's gate contract. This work may change where the loop runs,
  but it must not add a plan-approval gate, add a post-push Codex review pass,
  merge PRs, weaken the no-deferral rule, or move traceability/status
  reconciliation before reviews are clean.
- Keep the GitHub issue thread as the durable workflow record. Server-side
  loops may cache comments and return hashes, but markers, phase records,
  review-cycle records, decision records, and final reports remain issue
  comments per ADR-029.
- Treat per-step Markdown files as workflow prose packaging, not a new workflow
  DSL. The executable schema remains `.ground-control.yaml` plus
  `gc_get_repo_ground_control_context`; stages are the existing
  `gc_resolve_workflow_route` stage names unless a new stage is added to that
  parser and its tests.
- Treat new loop tools as deterministic MCP orchestration boundaries. They may
  invoke existing review, verify, posting, CI, and Sonar helper paths; they
  should return compact terminal envelopes, not raw reviewer transcripts,
  command logs, or open-issue dumps to the parent.
- Keep Temporal compatibility as a boundary property only. These tools should
  look like future activities with typed inputs/outputs, but this bridge work
  must not introduce Temporal state, queues, worker processes, or a separate
  workflow engine.
- Keep five concepts distinct: workflow prose packaging, executable tool
  schemas, durable issue-thread records, operational telemetry, and future
  Temporal execution history. Moving a loop server-side does not make its cache
  or telemetry a workflow state store.
- Preserve ADR-028's project/tenant boundary. Bridge tools are repo/issue
  scoped today; future backend workflow APIs must resolve the Ground Control
  project through `ProjectService` and project-scoped repositories, while SaaS
  tenant-to-Temporal-namespace mapping remains a later ADR.

## Cross-Cutting Concerns to Reuse

- **Config and repo containment:** reuse `ensureGitRepo`,
  `parseGroundControlYaml`, `resolveRepoRelativePath`, `assertRealpathInRepo`,
  `normalizeRoutingConfig`, `normalizeTelemetryConfig`, and the strict
  unknown-key rejection pattern. Any new path-valued input for step files,
  cache files, summaries, or telemetry must be repo-relative, reject absolute
  paths and `..`, and pass realpath containment before writes.
- **Routing and telemetry:** reuse `gc_resolve_workflow_route`,
  `DEFAULT_IMPLEMENT_ROUTING_STAGES`, `gc_log_step_telemetry`,
  `buildTelemetryRecord`, `buildTelemetryRelPath`, and
  `sanitizeTelemetryBranch`. Do not create a second routing matrix or telemetry
  writer inside the skill or per-step files.
- **Review state:** reuse the existing marker parsers/evaluators for Codex
  pre-push cycles, Codex verify cycles, and test-quality cycles. The cycle
  counter is anchored to the issue thread; branch remains audit context, not a
  reset key.
- **Decision records and final records:** reuse `gc_post_decision_record`,
  `gc_post_final_report`, `gc_render_pr_body`, `detectSensitiveBodyContent`,
  reserved-marker rejection, GitHub body-size handling, and the existing
  `gh api` argv-style posting path.
- **Issue-comment reads:** build on `getOwnerRepo` and
  `readIssueCommentBodies`. If a cache/hash layer is added, it must preserve
  pagination, marker escaping, and stale-cache detection; it must not become a
  source of workflow truth.
- **CI and Sonar:** keep the existing Step 10 caps as tool-enforced behavior:
  queued-too-long at 5 minutes, total in-progress cap at 45 minutes, and a
  terminal envelope with failed step names plus a bounded log summary. For
  Sonar, reuse `.ground-control.yaml::sonarcloud` validation and preserve the
  final-report guard that `sonarStatus='skipped'` is legal only when the repo
  has no SonarCloud config.
- **Policy checks:** extend `tools/policy/checks.py` and
  `architecture/policies/adr-policy.json` when SKILL structure changes affect
  workflow guardrails. Keep `make policy` as the repo-native completion gate.

## Security and Validation Layers

- **MCP Zod schemas:** new tools such as `gc_codex_review_cycle`,
  `gc_test_quality_review_cycle`, `gc_watch_ci_run`,
  `gc_watch_sonar_analysis`, and issue-thread cache helpers need positive
  issue/PR/run ids, bounded enums for statuses and `next_action`, bounded
  strings/arrays, and rejection of `decision: "defer"`.
- **GitHub/Sonar token handling:** call `gh`, `git`, and Sonar access paths
  with argv arrays or existing MCP/server integrations. Do not put tokens,
  URLs with bearer values, raw environment dumps, prompts, or transcripts in
  process arguments, telemetry records, comments, PR bodies, or error messages.
- **Raw payload summarization:** CI logs, Sonar issue lists, hotspots, Codex
  review text, and test-quality findings must be summarized before returning
  to the parent. Raw drill-down can be available to the worker/tool that needs
  it, but the parent envelope should carry only summary, top failing steps,
  file hints, counts, and stable URLs.
- **Error envelopes:** expected gate failures should return structured MCP
  results with `ok`, `error`, `message`, ids, counts, and `next_action`.
  Unexpected process failures should strip secrets and avoid stack traces in
  returned text.
- **OS/filesystem exposure:** branch names, step ids, cache keys, and issue
  titles must never become raw path segments. Reuse the telemetry branch
  sanitizer pattern or add a narrowly named equivalent when a different token
  shape is required.
- **Backend REST boundary:** no backend controller is required for this scope.
  If a later implementation adds one, it must use Bean Validation,
  `@ConfigurationProperties`, `ProjectService` scoping,
  `GroundControlException` through `GlobalExceptionHandler`/`ErrorResponse`,
  `ActorHolder`/`ActorFilter`, and the api/domain/infrastructure import
  boundary.

## Maintainability and Extensibility

- Keep one canonical review-loop rules document and reference it from Step
  6.5, Step 6.6, `/ship`, and the new orchestrator. Do not copy the rules into
  every per-step file.
- Keep per-step files self-contained for execution but thin on cross-cutting
  policy. They should name the stage id, required cached inputs, expected
  structured result, and canonical tool calls; they should not restate schemas
  that MCP tools already validate.
- The review-cycle seam should be parameterized by reviewer (`codex`,
  `test-quality`) and effective cap source, not by copied near-identical
  functions with separate marker/state rules.
- The watcher seam should be provider-specific at the edge only:
  `gc_watch_ci_run` may initially target GitHub Actions and
  `gc_watch_sonar_analysis` may target SonarCloud, but terminal envelopes
  should leave room for another CI or analyzer without changing the parent
  workflow.
- The issue-thread cache seam should be content-addressed by issue number plus
  a returned hash/delta token. It should not be branch-keyed and should not be
  used to bypass a fresh read when posting failed or marker state is uncertain.
- The telemetry seam remains append-only JSONL under `.gc/telemetry/`.
  Provider token counters are optional; wall time, step id, tier, model,
  outcome, issue, and sanitized branch remain the stable core.

## Gotchas and Anti-Patterns

- Current repository ADRs have amended older GC-O007 prose: ADR-029/ADR-036
  now describe configurable pre-push caps with default 1, while older issue or
  requirement text may still say hard cap 3. Resolve the contract through the
  current ADR/config path before coding; do not bake a stale cap into new
  tools.
- Do not make subagents the enforcement boundary. The MCP layer owns caps,
  marker writes, GitHub posting, secret filtering, and durable-record success
  signals.
- Do not let a successful tool invocation count as a review cycle if the
  findings/decision record failed to post. Failed durable-record posting must
  return a structured `*_post_failed` result and be retried after the issue is
  fixed.
- Do not let per-step files hide workflow drift from policy. If the monolith is
  split, policy checks must follow the new source layout rather than silently
  checking only an obsolete shell.
- Do not treat telemetry or local cache files as audit evidence. They can be
  missing, truncated, or local-only without changing the issue-thread record.
- Do not duplicate GitHub clients, Sonar clients, decision-record renderers,
  error shapes, cap evaluators, or marker families for convenience.
- Do not return raw CI logs, raw Sonar payloads, raw review transcripts, full
  issue threads, full PR bodies, or prompts to the parent unless there is a
  specific drill-down path and a bounded payload contract.

## Non-Goals

- No implementation of GC-O009 Temporal orchestration.
- No new database table, local durable state store, git notes, branch-keyed
  counters, or workflow DSL.
- No backend REST API or frontend UI change solely for this optimization.
- No change to the one-human-touchpoint model, no plan approval, no PR merge by
  agents, and no post-push Codex review driven by the SKILL.
- No provider-specific model policy embedded in requirements or per-step prose.
