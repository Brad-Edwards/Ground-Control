# Async Mechanical Execution Preflight

Issue #1473 lets long-running `gc_implement_mechanical` actions outlive one
MCP request. This note is architecture guidance only. It does not implement
background execution or change the workflow.

## Binding Contract

- Reuse the existing in-process job registry and `gc_codex_job` polling
  surface. Do not add a mechanical-only registry, polling tool, result schema,
  queue, worker, backend entity, or local state file.
- Keep the registry operational. It survives separate calls to the same MCP
  process, but it is not restart-durable workflow state. The GitHub issue
  thread, synchronization attestation, readiness record, final report, and
  workflow-run records retain their existing authority.
- Keep `runImplementMechanical` as the one synchronous action executor. The
  async path wraps that executor and stores its unchanged result; it must not
  copy action dispatch, validation, lifecycle emission, or failure shaping.
- The canonical `/implement` and `/quickfix` workflows must request background
  execution for `verify`, `publish`, and `monitor`. Those actions run
  repository-authored commands, final-tree gates, or bounded remote pollers
  that can exceed the client request timeout. `bootstrap`, `readiness`, and
  `finalize` remain synchronous because they contain bounded reads and writes,
  not a long polling or repository-command boundary.
- Follow the existing review-tool input convention with one optional `async`
  boolean. The standard workflows pass `async: true` for the three
  long-running actions. Omission preserves the current direct-caller behavior.
  Reject `async: true` for an action outside the closed long-running set rather
  than returning an unexpected job handle for a normally synchronous action.
- A start response is compact:
  `{ok:true,status:"running",job_id,kind}`. Polling returns the existing
  running envelope or `{ok:true,status:"done",...,result}`. `result` is the
  original mechanical envelope, including `action`, `agent_required`,
  `next_action`, and any structured repair evidence.
- Keep job completion separate from action success. An expected mechanical
  failure such as a red gate is a successfully completed job whose
  `result.ok` is false. Top-level `status:"failed"` is reserved for an
  unexpected thrown or rejected job. Do not flatten, translate, or duplicate
  the mechanical envelope at the job layer.

## Idempotency and Concurrency

- Background mechanical starts require a bounded opaque `idempotency_key`.
  The caller creates one key per logical attempt and reuses it until that
  attempt reaches a terminal job state. A deliberate retry after repair uses a
  new key.
- Scope a key to the canonical repository identity, issue, action, and a
  server-derived fingerprint of the normalized mechanical input. Reusing the
  same key with the same input returns the existing job handle, whether it is
  running or terminal. Reusing it with different input returns a stable
  `job_idempotency_conflict` refusal and starts nothing.
- Do not derive idempotency solely from action, issue, branch, or PR. The same
  action and identifiers are intentionally retried after the checkout or
  remote state changes. Do not include `async` or the idempotency key itself in
  the request fingerprint.
- Enforce single flight for background jobs that can operate on the same
  canonical checkout. A duplicate start reuses its job; a distinct active
  `verify` or `publish` attempt for that checkout receives a structured
  contention result. This prevents two gate or Git mutation sequences from
  racing after the first start call has returned.
- Bound registry capacity. Reap expired terminal jobs first and refuse new
  work when the bound is still reached. Never evict a running job to make room.
  The existing terminal TTL remains retention after completion, not an action
  timeout.
- Keep job ids opaque. Bound and shape-check `job_id` at the Zod polling
  boundary, and do not echo an arbitrary unknown id in an error message.

## Cancellation

`gc_codex_job` currently assumes its job function threads the supplied
`AbortSignal` to the child process. Mechanical actions do not currently honor
that signal across repository commands, Git/GitHub commands, Sonar fetches,
and polling sleeps.

The generic registry must therefore record whether a job is cancellable.
Until the complete mechanical call graph honors abort, canceling a mechanical
job returns a structured `job_not_cancellable` result and leaves the job
running to its ordinary terminal envelope. It must not return `cancelling`
while the action continues unaffected. If cancellation is later enabled, it
must cover every subprocess, fetch, retry delay, and poll sleep and must prove
that shell descendants are not orphaned.

## Canonical Incumbents

- **Job execution:** the registry currently exposed as `startReviewJob`,
  `pollReviewJob`, `cancelReviewJob`, and `REVIEW_JOB_TTL_MS` in
  `mcp/ground-control/lib.js`, plus the registered `gc_codex_job` tool. Generalize
  its internal vocabulary and options instead of wrapping it in a second
  registry. Preserve existing review/preflight callers.
- **Mechanical contract:** `gcImplementMechanicalZodShape`,
  `gcImplementMechanicalToolHandler`, `runImplementMechanical`, the action
  dispatcher, `failure`, and `commandFailure`. The async adapter adds only
  transport fields and job selection.
- **Configuration:** `getRepoGroundControlContext`,
  `resolveWorkflowPolicyCommand`, and `resolveWorkflowPrecommitCommand`. This
  issue needs no `.ground-control.yaml` key and no second parser.
- **Repository and requirement authorization:**
  `authorizeImplementMutationCheckout`,
  `authorizeRequestedRequirementUid`, `requestedRequirementUidAuthorization`,
  `ensureGitRepo`, launch-workspace binding, active-branch checks, and safe Git
  configuration checks. Background execution must run these at the same
  action boundaries as synchronous execution.
- **Process and credential boundaries:** fixed-argv `execFile`,
  `runImplementGitCommand`, `runImplementPreCommit`,
  `implementGateEnvironment`, explicit `gh --repo` selection, and Sonar
  authorization headers. No token, raw environment, or requirement identity
  moves into job ids, idempotency keys, telemetry, argv, or result metadata.
- **Errors and secret handling:** existing structured mechanical failures,
  `detectSensitiveBodyContent`, bounded diagnostics, and MCP `ok`/`err`
  wrapping. Normalize unexpected job errors before storage and polling; never
  return stack traces, raw command output, response bodies, or environment
  values.
- **Observability:** handler-level MCP tool telemetry and
  `createWorkflowRunLifecycleEmitter`. The start and poll calls remain normal
  MCP usage events, while the existing station emitter measures the underlying
  mechanical action. A background job is execution machinery, not a new
  station, lifecycle marker, review cycle, or ADR-036 step-telemetry record.

## Security and Validation Layers

- Zod owns the public `async`, `idempotency_key`, and bounded `job_id` shapes.
  The existing action-specific code owns semantic required-field checks. Do
  not create a second mechanical input schema in the registry.
- Canonical repo and issue authorization still occurs inside the reused
  executor before protected reads, mutations, or requirement-bound gates.
  Deduplication metadata must not authorize an action or bypass revalidation.
- Repository command text still comes only from the validated Ground Control
  context. The caller cannot submit a timeout, command, remote, refspec, token,
  or environment override through the job surface.
- `SONAR_TOKEN` remains process-local and reaches Sonar only in the
  Authorization header. GitHub and Git credentials remain in their existing
  host credential boundaries. The registry stores no credential-bearing
  diagnostic.
- Expected failures retain the bounded mechanical
  `{ok,error,message,agent_required,next_action}` shape under `result`.
  Unexpected failures use one bounded, scrubbed job error envelope.

## Contract Coverage

- Published schema and live registration cover `async`,
  `idempotency_key`, the action eligibility rule, and bounded `job_id`.
- Registry tests cover start, running poll, terminal success, unexpected
  failure, expected mechanical failure under `result`, duplicate reuse,
  idempotency conflict, contention, capacity, expiry, and the non-cancellable
  mechanical response.
- Mechanical adapter tests prove `verify`, `publish`, and `monitor` return
  compact handles without awaiting the action, then preserve their exact
  success or repair envelope through polling.
- Short-action tests prove `bootstrap`, `readiness`, and `finalize` keep their
  synchronous return contracts. Existing action, lifecycle-emission,
  authorization, sensitive-path, requirement-binding, and synchronization
  tests remain the behavioral authority.
- The MCP README, live tool-description parity test, `/implement` and
  `/quickfix` workflow prose, `docs/WORKFLOW.md`, and
  `docs/DEVELOPMENT_WORKFLOW.md` must describe one start-then-poll contract.
  ADR-036 records the generic job model amendment. Policy-triggered sync notes
  in ADR-054, ADR-090, and `docs/DOC_STYLE.md` must remain factual and narrow.

## Non-Goals and Anti-Patterns

- No longer MCP request timeout and no new timeout setting.
- No database table, backend controller, DTO, service, repository, migration,
  frontend UI, external queue, Temporal workflow, worker process, or local job
  file.
- No second polling tool or action-specific job registry.
- No duplicate mechanical result schema, validation hierarchy, lifecycle
  marker, station id, telemetry writer, GitHub client, or secret filter.
- No concurrent background mutation of one checkout, implicit retry under a
  completed idempotency key, or claim of cancellation without end-to-end abort.
- No raw job inputs, command output, error objects, Git remote URLs, tokens, or
  environment snapshots in logs, telemetry, handles, or poll envelopes.
