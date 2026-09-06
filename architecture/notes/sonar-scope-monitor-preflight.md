# Sonar Scope Monitor Preflight

Issue: #1559. Contract: the supplied issue; no requirement UID.

This is design guidance, not an implementation plan. #1559 owns prompt
termination and accurate diagnosis of the watcher. #1533 owns verification of
legitimate scope exclusions at readiness/finalize. Existing ADR-027, ADR-029,
ADR-031, ADR-036, and ADR-090 cover the boundaries; no new ADR is needed.

## Outcome and Evidence Boundaries

Keep three questions separate: can this analysis still arrive, did Sonar
evaluate it, and may this PR satisfy the readiness gate? Terminating a futile
watch does not answer the last question.

| Observation | Watcher meaning | Gate and workflow consequence |
| --- | --- | --- |
| Valid configuration without Sonar enabled | Existing configuration skip | Preserve its existing meaning; do not relabel it as path exclusion. |
| Authoritative scope decision excludes this PR | Terminal `out_of_scope`, with evidence | Record `skipped_station`, not a measured pass or findings; #1533 must verify the evidence before readiness. |
| Correct scan producer is terminal-skipped, but its scope reason is unproved | Stop waiting for that attempt; report the observed skip and unknown cause | `not_evaluable`; diagnose the producer/ownership decision, without alleging a token fault. |
| Component absent while analysis can still propagate | Bounded propagation wait | Absence alone proves neither exclusion nor a bad credential. |
| Analysis absent and current evidence establishes that no producer will publish it | Terminal missing-analysis diagnosis | Without scope proof, remain `not_evaluable`; no deterministic rerun instruction. |
| Sonar response with gate and complete issue/hotspot results | Existing evaluated outcome | Preserve quality gate OK plus zero open issues and zero open hotspots. |
| Needed observation fails on credentials, transport, or response validation | Preserve the specific failure | No fabricated finding, scope proof, or generic token attribution. |

The scope pre-check belongs in the shared watcher before the initial 60-second
sleep and propagation loop. Both direct `gc_watch_sonar_analysis` calls and
mechanical monitor calls need the same behavior. A verified exclusion needs
no Sonar credential: check applicability before requiring a token for an
otherwise unnecessary Sonar request. An absent token is a confirmed missing
capability only when that request is needed; it does not establish why CI
skipped. A 401/403 is not `sonar_watch_token_missing`.

CI completion alone does not prove Sonar propagation is complete. A successful
scan can publish later; a 404 can also mean a wrong project or inaccessible
component. Parse HTTP status and response shape separately. An error document
or malformed success body must not silently become repeated "not available"
polls. Preserve transient 429/5xx retries and bounded waits when publication
remains possible. Include pre-check calls, fetch/retry time, and sleeps in the
effective watch budget; the current loop starts its clock after initial wait
and has no fetch deadline. Do not promise an absolute cap without bounding I/O.

## Scope Authority and Extensibility

Ground Control has no `quality-path-filters.yaml` or `quality_ownership`
classifier incumbent. Those paths describe the consuming repository in the
issue. This checkout instead has `.github/workflows/sonarcloud.yml`,
`sonar-project.properties`, and `tools/sonar/assert_no_new_issues.py`. Do not
copy the consuming repository's ownership algorithm into Ground Control or
infer exclusions from extensions, docs-only labels, or Sonar source globs.

Use the consuming repository's canonical committed scope contract and its CI
decision. A check display name alone is insufficient authority. Bind evidence
to repository identity, PR number, current head and relevant base/diff
identity, project key, producer/workflow identity, run/check ID and attempt,
terminal conclusion, contract revision, and the actual exclusion decision
(such as a validated boolean `run_sonar=false`). Identify how the producer's
decision is obtained and authenticated; ordinary check-run metadata does not
itself contain the classifier output.

Reject stale attempts, conflicting producers, changed heads, incomplete API
pagination, malformed booleans, and unsupported contracts as exclusion proof.
Account for the producer's actual tested revision, including a synthetic merge
revision where applicable; do not attach the current head to unrelated checks.
If classification needs paths, use the complete PR diff, including deletion
and rename semantics defined by that classifier. An empty or truncated result
is not evidence that every path is excluded. A contract modified by the PR
must be tied to the version and trust policy actually used by CI; arbitrary
working-tree changes cannot grant themselves an exemption.

The narrow extensibility seam is the acquisition of a repository's scope
decision and expected producer identity, feeding one normalized watcher
outcome. Support a different workflow/check name or ownership producer there,
not by adding branches to every consumer. Reuse authenticated, bounded CI
evidence where available. Any needed non-secret repository selector belongs
in the existing config normalization path with unknown-key rejection and
parser tests. Do not invent a provider registry, arbitrary shell-command
configuration, new ownership schema, or caller-controlled `skip_sonar` flag.
Never execute PR-supplied classifier code in the credential-bearing MCP host
merely to learn scope. Missing integration yields unknown scope, not a waiver.

## Canonical Incumbents and Cross-Cutting Checks

- **Transport and semantic validation:** keep the thin Zod tool registration
  in `tools/review-cap-disposition.js`, `gcImplementMechanicalZodShape`, and
  `validateWatchSonarAnalysisInput`. Direct library callers also need validated
  PR identity and timings. Evidence is server-acquired output, not a trusted
  caller attestation. Keep the synchronous executor and async wrapper aligned.
- **Repository authorization and GitHub access:** build on `ensureGitRepo`,
  `getOwnerRepo`, and the existing launch-workspace authorization helpers
  (`resolveMcpLaunchWorkspaceAuthorization`, `authorizeImplementRepoRoot`).
  The Sonar watcher currently only checks for a Git repository; new privileged
  GitHub reads must not use that as sufficient authorization. Reuse fixed-argv
  `execFile` and established JSON/pagination handling in `pr-review-shared.js`;
  `pr-review-context.js` supplies identity/completeness patterns, not a ready
  scope attestation. Keep destinations explicit and bound to the authorized
  origin. `ci-watcher.js` already aggregates runs by head, but its latest-run
  fallback and branch-wide success are insufficient proof for a PR exclusion.
- **Configuration parsing:** use `parseGroundControlYaml` and
  `normalizeSonarcloudConfig`. The existing
  `_readSonarCloudConfigFromRepo` conflates missing, malformed, and absent
  Sonar configuration into `null`; do not use that permissive result as scope
  evidence or let an invalid declaration become gate clearance. Preserve
  valid no-Sonar compatibility explicitly, with parser errors distinguished.
- **Secrets, environment, and OS exposure:** `lib/server-env.js` and its
  inventory/parity tests own startup provisioning from the launch-directory
  `.env` only. No ambient fallback, new env map, secret path, or config token.
  `_sonarAuthHeader` supplies the token solely to in-process HTTPS requests at
  `SONAR_BASE_URL`; never put it in subprocess argv, URLs, evidence, exports,
  logs, or errors. Repo paths and artifact locations retain containment and
  realpath checks; do not follow arbitrary response URLs with credentials.
- **Errors and observability:** reuse `_sonarFetchWithRetry`, structured
  watcher errors, mechanical `failure()`, `bounded`,
  `detectSensitiveBodyContent`, and MCP `ok`/`err` wrapping. Scrub and bound
  nested evidence and watcher messages at their origin: `failure()` sanitizes
  its message, not its `extra` object, and async job results retain nested
  payloads. Return normalized facts and stable reasons, not raw GitHub/Sonar
  bodies, stderr, request headers, or stacks. No new exception hierarchy.
- **Outcome projection:** centralize interpretation in `lib/sonar-gate.js`;
  `implement/publish.js::runMonitor` consumes it. Today `skipped:true` passes
  `sonarGatePassed` unconditionally. Do not encode a new exclusion with that
  legacy boolean and accidentally authorize advancement. Lifecycle emission,
  `sonarStationResult`, and ADR-090's existing station vocabulary distinguish
  `skipped_station` from `not_evaluable`, pass, and fail; no new station enum
  or synthetic findings are needed. Successful observation is not readiness.
- **Other consumers and persistence:** inspect the shared watcher's adapter
  in `gc-integrate/exec-file-async.js` and guard in `run-plan-action.js`; the
  adapter currently collapses errors into skips and drops evidence. Preserve
  evidence/reason without granting integration a new readiness bypass.
  `completionShape` accepts a string, but `tools/post-decision-record.js`,
  `validateFinalReportInput`, `FINAL_REPORT_SONAR_STATUSES`, and
  `doc-coverage-2.js` enforce narrower completion contracts.
  `renderSonarStatus` labels skips as no configuration. #1533 must align those
  consumers and verify current evidence, not simply widen their enums.
  Until then, report that gate limitation explicitly. Reuse issue-thread
  records and execution obligations for durable diagnosis; replace a stale
  token obligation through normal reconciliation when evidence changes.
  The job registry and `.gc/sonar` exports are not readiness authority.
- **Workflow and policy:** Step 11 routing, Step 10 handoff, Quickfix Q11,
  registered tool descriptions, and MCP runtime documentation must agree.
  `architecture/policies/adr-policy.json` requires workflow guardrail changes
  to sync `docs/DEVELOPMENT_WORKFLOW.md`, `docs/WORKFLOW.md`, and a listed
  gate-model record or sibling lane. Preserve `tools/policy/ci_strictness.py`,
  its tests, the Sonar workflow, and zero-open-issues script. Prompt text
  cannot substitute for a server-enforced outcome or verification boundary.

## Regression Boundaries and Non-Goals

Reuse the Node mocked-fetch/temporary-repository watcher tests,
`sonar-gate.test.js`, mechanical monitor and lifecycle tests, integration
mapping tests, environment provenance tests, and policy contracts. Cover
verified exclusion with and without a token (no sleep or Sonar fetch), an
unexplained terminal skip, pending/rerun/stale checks, successful CI followed
by delayed analysis, wrong project/404, auth failure, malformed responses,
incomplete evidence, changing PR identities, and clean/open-findings results.
Assert evidence, next action, station classification, and preserved readiness
refusal; use injected runners/time so tests do not wait through the cap.
Run `make mcp-test` and `make policy` for the implementation.

No cancellation retrofit: `lib/async-job-registry.js` must keep mechanical
jobs non-cancellable until the full call graph honors abort across children,
HTTP requests, retry delays, and sleeps. No new job store, tool, backend,
database, requirement, or merge authority. No changes to finding severity,
pagination coverage, the repair-cycle cap, CI branch protection, or the
consuming repository's ownership rules. Do not consume a findings fix cycle
or ask for token provisioning on an exclusion. Do not implement #1533's
gate-clearance policy or #946's credential provisioning as part of this fix.
