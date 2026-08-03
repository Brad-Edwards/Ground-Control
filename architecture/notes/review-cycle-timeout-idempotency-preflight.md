# Review-Cycle Timeout and Retry Preflight

Issue #943 hardens the public `gc_codex_review_cycle` and
`gc_test_quality_review_cycle` MCP boundaries after synchronous calls exceeded
the client timeout, continued server-side, posted durable records, and consumed
the review cap without returning their terminal envelopes. This note is
architecture guidance only. It does not implement the change.

## Binding Contract

- The two public cycle-wrapper tools are asynchronous by contract. A call must
  return a compact background-job handle without awaiting the reviewer,
  findings record, cycle marker, station record, or decision record.
  `async` omitted or `true` means background execution; `async: false` must not
  reopen the synchronous MCP path.
- Keep `runCodexReviewCycle` and `runTestQualityReviewCycle` as the synchronous
  internal executors whose exact result is stored under the terminal job's
  `result`. Async is a transport boundary, not a second review implementation.
- Reuse `startAsyncJob`, `pollAsyncJob`, and the existing `gc_codex_job`
  surface. Do not add reviewer-specific registries, polling tools, queues,
  workers, backend entities, or local job files.
- Each cycle start requires one bounded `idempotency_key` for one logical
  attempt. A caller repeats that key when the start response or job handle is
  lost. It uses a new key only after the attempt is terminal and the reviewed
  tree has intentionally changed for a later cycle.
- Scope idempotency to the canonical repository path, issue number, and
  reviewer, with a server-derived fingerprint of the normalized cycle input.
  Reusing the key and input returns the same running or terminal job; changing
  input under the key returns `job_idempotency_conflict`.
- Enforce single flight per canonical repository, issue, and reviewer. Two
  different keys must not concurrently read the same prior-cycle count and
  both post the same next cycle. Codex and test-quality use disjoint scopes and
  retain their disjoint marker families.
- Preserve the existing result layering. `status: "done"` means the background
  function returned; `result.ok` and `result.status` still decide whether the
  review was clean, found issues, hit the cap, or failed to complete its
  durable writes. Do not flatten a review failure into a job failure.

## Durable State and Failure Ordering

- The async registry is bounded, process-local waiting state. The GitHub issue
  thread remains the durable workflow record and the source of the per-reviewer
  cap count under ADR-029.
- Preserve the established write order: findings record, any station
  re-observation resolution, cycle marker, then decision record. A job handle
  does not consume a cycle; only the existing durable marker does.
- Reuse the existing cap evaluators, marker builders/parsers, findings
  renderers, `runPostDecisionRecord`, and structured partial-post envelopes.
  Do not create an async cap counter, duplicate marker family, or job-backed
  decision-record schema.
- A same-key retry while the job is retained must return that job rather than
  running the reviewer or posting again. A `job_not_found` after process
  restart or terminal expiry is not proof that no durable write occurred.
  Workflow prose must require a fresh issue-thread read before any new logical
  attempt; it must not instruct callers to blindly start a fresh job.
- Do not claim exactly once execution across MCP process loss. Achieving that
  would require a durable attempt identity and reconciliation protocol, which
  is outside this maintenance change. The issue #943 guarantee is that a live
  server cannot lose a client response and then duplicate the same retained
  logical attempt.
- Cancellation is not rollback. GitHub comments are not transactional, and
  the current signal is guaranteed only through the reviewer child process,
  not every subsequent `gh api` write. Cycle jobs must not advertise
  cancellation as proof that no record was posted. Unless end-to-end,
  phase-aware cancellation is proved, register cycle jobs as non-cancellable
  and rely on the existing bounded child timeout.

## Canonical Incumbents

- **Transport and retry:** `mcp/ground-control/lib/async-job-registry.js`,
  `gc_codex_job`, its bounded job-id/key constants, capacity, terminal TTL,
  error scrubbing, idempotency conflict, and single-flight support.
- **Cycle orchestration:** `mcp/ground-control/lib/review-cycle-seam.js`,
  especially `_runReviewCycleShared`, `runCodexReviewCycle`, and
  `runTestQualityReviewCycle`.
- **Public schemas:** the Zod registrations in
  `mcp/ground-control/tools/post-decision-record.js`. Reuse the async registry's
  idempotency-key bounds and pattern rather than copying a validator.
- **Repository authorization:** `ensureGitRepo`,
  `resolveMcpLaunchWorkspaceAuthorization`, `authorizeImplementRepoRoot`, and
  `assertSafeImplementCheckoutConfiguration`. Perform these checks before job
  lookup or creation so an idempotency hit cannot bypass the current
  launch-workspace and repository-identity boundary.
- **Codex durable writes:** `runCodexReview`,
  `postCodexReviewFindingsComment`,
  `postCodexReviewPrePushCycleMarker`, and the existing pre-push cap evaluator.
- **Test-quality durable writes:** `runTestQualityReview`,
  `postFindingsRecordAndCycleMarker`, and the existing test-quality cap
  evaluator.
- **Decision records and GitHub protection:** `runPostDecisionRecord`,
  `detectSensitiveBodyContent`, reserved-marker handling, GitHub body-size
  caps, `extractGhErrorMessage`, and argv-based `gh api` execution.
- **Workflow guidance:** `skills/implement/steps/step-06.5-codex-review.md`,
  `step-06.6-test-quality-review.md`, `_review-loop-rules.md`, and the
  `/quickfix --review` references. They must describe the same required key,
  start, poll, retry, and missing-job behavior.
- **Observability:** handler-level MCP tool telemetry and the existing review
  station-attempt emitter. Starts and polls remain tool calls; a background job
  is not a new station, review cycle, lifecycle marker, or ADR-036 routing
  stage.

## Security and Validation Layers

- Zod owns positive issue numbers, existing cycle options, and the bounded
  public `idempotency_key`. Runtime job validation remains defense in depth;
  do not add a parallel validation hierarchy.
- Canonicalize and authorize `repo_path` through the existing launch-workspace,
  repository-identity, and safe-Git-configuration checks before deriving a
  namespace or single-flight scope. Then let the reused review executor
  perform its existing branch, issue, cap, and `.ground-control.yaml`
  validation. An idempotency match never authorizes a different repository or
  bypasses any of those checks.
- Keep review configuration in `.ground-control.yaml` through
  `getRepoGroundControlContext`; invalid reviewer-cap configuration remains a
  structured refusal. This issue needs no new config or environment setting.
- Keep the reviewer subprocess boundaries unchanged: fixed argv, prompt on
  stdin, bounded output and child timeout, `AbortSignal` where supported, and
  `ANTHROPIC_API_KEY` stripped from the test-quality child. Do not put the
  idempotency key, prompt, diff, token, raw environment, or repository contents
  in process argv.
- Keep model-controlled durable bodies behind reserved-marker disarming,
  `detectSensitiveBodyContent`, and GitHub body caps. Store and return only
  bounded, scrubbed unexpected job errors; never include stack traces, raw
  command output, prompts, diffs, tokens, or environment values.
- MCP usage telemetry keeps its closed shape and must not record tool
  arguments, job fingerprints, idempotency namespaces, or keys. Job reuse does
  not emit another review-station result because the reviewer did not run
  again.
- The Spring `ErrorResponse`/`GlobalExceptionHandler`, Bean Validation,
  `ActorHolder`, repositories, services, and backend transaction model are not
  on this path. Do not add a backend API merely to imitate a transaction that
  GitHub comments cannot provide.

## Extensibility Seam

The extension point is the existing generic job options:
`idempotencyKey`, `idempotencyNamespace`, `fingerprint`, `executionScope`,
`singleFlight`, and `cancellable`. A future third reviewer can use the same
cycle seam with a distinct reviewer id and marker family without changing the
polling tool or job envelope. Do not parameterize timeouts, commands, tokens,
or arbitrary execution scopes through MCP input.

## Contract Coverage

- Live tool-schema coverage must prove both cycle wrappers publish the bounded
  idempotency key and cannot select a synchronous path.
- Handler coverage must prove an unresolved cycle returns a job handle
  immediately, same-key starts reuse one running and one terminal job, changed
  input conflicts, and different keys in one reviewer scope are contended.
- Polling coverage must preserve the exact terminal cycle envelope, including
  `next_action`, findings summary, coverage metadata, and durable-record URLs.
- Failure coverage must distinguish top-level job rejection from
  `status: "done"` with a non-clean or `ok: false` review result.
- Existing cap, post ordering, secret filtering, reserved-marker, body-size,
  cancellation, station emission, and decision-record tests remain the
  behavioral authority. Add a regression that a duplicate retained start
  invokes no reviewer and performs no GitHub post.
- Tool descriptions, MCP README, workflow prose, ADR-036's async amendment,
  and policy checks that pin the review loop must agree on the contract.

## Non-Goals and Anti-Patterns

- No longer MCP request timeout and no new timeout/config knob.
- No rewrite of review parsing, findings aggregation, cap policy, override
  authorization, station-observation recovery, or decision rendering.
- No backend controller, DTO, service, repository, migration, frontend UI,
  database table, external queue, Temporal workflow, worker, or local state
  file.
- No second job registry, polling surface, result envelope, exception
  hierarchy, marker family, GitHub client, secret filter, telemetry writer, or
  workflow loop.
- No synchronous fallback hidden behind omitted `async`, no automatic retry
  with a new key, no parallel jobs for one reviewer/issue, and no claim that a
  cancelled or missing job rolled back GitHub writes.
- No idempotency key, namespace, canonical path, prompt, diff, or raw error in
  durable comments, telemetry, logs, child argv, or user-facing envelopes.
