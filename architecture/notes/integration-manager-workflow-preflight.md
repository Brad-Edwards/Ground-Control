# Approved PR Integration Manager Preflight

Issue #989 / GC-O011 introduces an integration-manager workflow for preparing
maintainer-approved pull requests after the base branch moves. This note is
architecture preflight guidance only. It does not implement the workflow,
change `.ground-control.yaml`, create tools, or enable enqueue or merge modes.

## Architectural Boundaries

- Preserve ADR-029's one-human-touchpoint contract. The integration manager may
  prepare PR branches and report readiness, but it must not merge, squash,
  auto-merge, enqueue, approve, or ratify a PR. No ADR amendment enabling
  enqueue or merge exists in this preflight, so those modes must refuse even if
  a caller passes a flag.
- Keep the Ground Control MCP server as the privileged side-effect boundary.
  Discovery, GitHub writes, lock acquisition, branch updates, force-with-lease
  pushes, CI watch, and SonarCloud watch should run through MCP tools and
  argv-style `gh` / `git` calls on the host. Claude or Codex skill prose must
  not become the layer that owns those side effects.
- Keep `.ground-control.yaml` as the repo configuration boundary. Approval
  signal, target base branch, and any future ordering or mode policy must be
  loaded through `gc_get_repo_ground_control_context` and the existing strict
  parser path, not by ad hoc YAML reads in a skill.
- Treat the integration plan as a durable workflow artifact when the run is
  issue-anchored. Use ADR-029 issue-thread comments or an MCP-rendered record
  for plan, blocked entries, halt reasons, and final readiness. Local files,
  telemetry, terminal output, and agent memory are operational only.
- Keep the lane outside the Spring product model unless a later requirement
  asks for product-visible workflow executions. No backend controller, DTO,
  JPA entity, Flyway migration, REST endpoint, or frontend UI is required for
  a prepare-only MCP/skill workflow.
- Keep queue semantics distinct from `/implement` review cycles. An
  integration run serializes already-open PRs; it is not another
  implementation cycle, requirement transition, review cap, or traceability
  reconciliation state machine.

## Cross-Cutting Concerns To Reuse

- **Repo and config resolution:** reuse `ensureGitRepo`, `getOwnerRepo`,
  `parseGroundControlYaml`, `normalizeWorkflowConfig`,
  `resolveRepoRelativePath`, `assertRealpathInRepo`, and
  `gc_get_repo_ground_control_context`. Do not trust `GH_REPO` when the target
  repo has a GitHub origin, and stop if an explicit target slug conflicts with
  the repository remote.
- **GitHub API boundary:** reuse the existing `gh api` argv-based pattern,
  owner/repo resolution, sensitive-content checks, bounded output envelopes,
  and issue-comment marker conventions. Do not add a second GitHub client or
  let agents call `gh`, `git`, or `curl` from their sandboxes.
- **Locking:** reuse the existing `proper-lockfile` dependency and the
  `acquireKnowledgeLock` design pattern: realpath-based identity, stale-lock
  expiry, lock refresh, bounded retries, and idempotent release. Do not reuse
  the knowledge lock itself for integration work; the lock identity is the
  target repository lane, not the knowledge directory.
- **CI and Sonar watchers:** reuse `gc_watch_ci_run` and
  `gc_watch_sonar_analysis`. Do not duplicate poll loops, raw log scraping,
  token handling, Sonar pagination, or final-report readiness semantics.
- **Completion gate:** run the target repository's configured
  `workflow.completion_command` from the target repo context. Treat that
  command as trusted repository configuration, not PR-authored input, and do
  not interpolate labels, branch names, issue text, or PR titles into it.
- **Workflow publication:** if new skill or step files are added, keep
  `docs/DEVELOPMENT_WORKFLOW.md`, `mcp/ground-control/README.md`, ADR-029,
  ADR-036, and workflow policy checks synchronized where their existing sync
  rules apply. Run `make policy`; after policy or ADR workflow-surface changes,
  also run `make sync-ground-control-policy` and `make policy-live` when a
  live Ground Control instance is reachable.
- **Testing:** use MCP unit tests for parser, mode refusal, discovery/order,
  lock behavior, branch command assembly, and watcher envelopes. Use
  `@WebMvcTest` only if this unexpectedly adds a backend controller; do not
  rely on Testcontainers for SonarCloud coverage.

## Security And Validation Layers

- **Target repository shape:** accept an absolute repo path and, if needed, a
  validated `owner/repo` slug. Resolve the canonical repo root before any
  filesystem or GitHub operation. Reject path traversal, non-Git directories,
  non-GitHub remotes, and slug/remote mismatches.
- **Config schema:** add any approval-signal configuration under a strict
  parser-owned shape such as `workflow.integration_manager`. The default is one
  required label, `approved-for-integration`. Validate labels as non-empty,
  bounded strings without control characters. Keep `workflow.base_branch` on
  the existing safe Git-ref validator.
- **MCP argument schemas:** validate positive PR/run numbers, bounded enums for
  mode and status, explicit prepare-only default, and known `next_action`
  values. Expected refusals should return structured `ok: false` envelopes
  with stable `error`, `message`, and `next_action` fields.
- **OS and process exposure:** call `gh` and `git` with argv arrays. Never place
  GitHub tokens, Sonar tokens, provider API keys, raw prompts, raw diffs, or
  command transcripts in argv, issue comments, telemetry, or returned errors.
  SonarCloud must keep using `SONAR_TOKEN` in the Authorization header only.
- **Worktree isolation:** prepare each PR in a dedicated worktree whose path is
  sanitized, repo-contained or under a controlled temp root, and cleaned up on
  normal completion or abort. Never run `git merge`; use a rebase/update flow
  and push only the PR head branch with explicit `--force-with-lease` semantics
  when a push is needed.
- **Error publication:** blocked PR details may name files, commands, check
  names, run URLs, and concise failure summaries. They must not dump full CI
  logs, full Sonar exports, raw conflict files, environment variables, or
  secrets into public issue-thread records.
- **Consultation stops:** clause (h) is a whole-run halt, not a per-PR block.
  Genuine ambiguity, conflicting authoritative inputs, material oversight, or
  any fix that would silence tests/docs/standards must preserve state and ask
  through the invoking interface before processing later queue entries.

## Maintainability And Extensibility

- Make discovery explicitly label-based for the first slice. Do not infer
  approval from comments, review approvals, branch names, authorship, or CI
  status unless a later config shape adds that as a named policy.
- Sort the queue by an explicit deterministic policy and render the policy in
  the plan. A safe default is PR number ascending. If maintainers later need
  approval-time or priority ordering, the seam belongs under
  `workflow.integration_manager.ordering`, not in GitHub's incidental API
  return order.
- Keep modes closed and typed: `prepare`, `enqueue`, `merge`. `prepare` is the
  default and the only executable mode under the current ADR set. `enqueue` and
  `merge` are reserved values that return refusal envelopes until ADR-029 /
  GC-O007 are amended.
- Keep failure categories explicit: PR-blocked, queue-wide failure, and
  consultation halt. These are different outcomes with different continuation
  rules; do not collapse them into a generic failed status.
- Keep lock state and run telemetry separate from durable workflow evidence.
  A lock file prevents concurrent writers; it is not the integration plan, the
  readiness record, or the blocked-PR ledger.
- Prefer parameterizing the existing MCP watcher and rendering seams over
  adding large new abstractions. A new helper is justified when it centralizes
  a real shared invariant, not because two command snippets look similar.

## Gotchas And Anti-Patterns

- Do not add a merge queue by naming it "prepare" or "ready." Updating PR
  branches is allowed; enqueuing or merging is still a human-owned action until
  an explicit ADR change lands.
- Do not discover "approved" PRs by querying all green PRs. The label is
  sufficient and necessary by default; absent label means absent queue entry.
- Do not begin branch mutation before rendering the ordered plan with base
  branch, order, approval signal, and per-PR readiness assessment.
- Do not let two path spellings for the same checkout acquire two locks. Lock
  by canonical repo identity, not by raw user input.
- Do not let one blocked PR poison unrelated entries unless the failure
  indicates the base branch, repo configuration, credentials, lock, or shared
  infrastructure is broken.
- Do not weaken gates while resolving conflicts. Removing tests, stubbing docs,
  skipping format/lint, bypassing `make policy`, or accepting red CI/Sonar is a
  consultation halt, not a mechanical fix.
- Do not use CI/Sonar watcher `skipped` states as readiness when the target
  repo is configured for those gates. A skipped SonarCloud result is only valid
  when the target repo has no `sonarcloud` block.
- Do not create duplicate schemas for approval labels, queue entries,
  readiness records, conflict records, or mode enums in skill prose and MCP
  tools. The MCP input/output schema is the executable contract.

## Non-Goals

- No implementation of the integration-manager workflow in this preflight.
- No enqueue, merge, auto-merge, branch protection, or GitHub merge-queue
  integration.
- No change to GC-O007 / ADR-029's single human merge touchpoint.
- No new workflow engine, Temporal state, database table, local durable state
  file, git notes, or second GitHub client abstraction.
- No backend REST API, frontend UI, or product-visible workflow execution
  model unless a later requirement explicitly asks for it.
- No replacement for `/implement`, `/quickfix`, CI, SonarCloud, Codex review,
  test-quality review, or traceability reconciliation.
