# Maintainer Pull Request Review Skill Preflight

Issue #1535 adds a maintainer-facing review lane for an existing pull request.
This note is architecture preflight guidance only. It does not add the skill,
register an MCP tool, inspect or modify a pull request, change a branch, post a
comment, or close an issue.

## Architectural Boundaries

- Make the workflow an agent-neutral sibling under `skills/`, not an
  `/implement` mode. It reviews contributor work that already has a PR and may
  have been created outside Ground Control. It must not manufacture an issue
  anchor, review-cycle counter, decision record, requirement transition, or
  readiness marker.
- Keep review and remediation as separate capabilities. The default capability
  reads PR state and returns findings through the invoking interface. The
  mutation capability is unavailable to the normal review path and is called
  only after the user explicitly asks for changes. A boolean asserted by the
  model is not cryptographic proof of user authority; the executable contract
  can require an explicit authorization field and bind it to the reviewed PR
  identity, but the host conversation remains the source of that authority.
- Keep the MCP server as the repository and GitHub boundary. The skill must not
  invoke `gh`, `git`, or `curl`, handle tokens, or construct shell commands.
  Read-only GitHub collection, branch preparation, base merge, verification,
  push, comment posting, and issue closure use registered MCP tools with zod
  input shapes and thin handlers.
- Keep the current worktree throughout remediation. Do not create a worktree,
  clone, or temporary checkout. The mutation boundary must prove that the
  canonical `repo_path` is the MCP launch checkout and that the checked-out
  branch is the PR head branch before editing or pushing.
- Preserve contributor history. Updating a stale PR means a real merge of the
  freshly fetched integration branch into the feature branch. Do not rebase,
  reset, squash, force-push, auto-select a conflict side, or rewrite unrelated
  commits.
- Keep user-owned merge unchanged. The lane gives a recommendation and may
  remediate when authorized; it never approves, enables auto-merge, queues,
  merges, or closes the PR.
- Keep the review evidence ephemeral. ADR-029's issue-thread record remains the
  contract for `/implement` and `/quickfix`; the read-only maintainer review
  intentionally posts no marker, finding, review, PR comment, or issue comment.
  One remediation summary is permitted only after an authorized change is
  pushed. Post-merge issue closure is a distinct, merge-gated action.
- Keep this outside the retired backend product model. No controller, DTO, REST
  endpoint, service, repository, database table, migration, or frontend surface
  is needed for a local skill plus MCP workflow.

## Canonical Incumbents To Reuse

- **Tool registration and envelopes:** use the existing zod plus thin-handler
  pattern in `mcp/ground-control/tools/*.js`, the `lib.js` barrel, and
  `tools/respond.js`. Expected failures return bounded `{ok:false, error,
  message, next_action}` envelopes. Do not introduce an exception hierarchy or
  a second response envelope.
- **Repository identity:** reuse `ensureGitRepo`, `getOwnerRepo`,
  `readGitIdentity`, `authorizeImplementRepoRoot`, and the immutable MCP launch
  authorization. The checkout's GitHub `origin` is authoritative; an optional
  `owner/repo` input can only be an assertion checked against that remote.
  Never allow `GH_REPO` to select an alternate destination.
- **Repository configuration:** reuse `getRepoGroundControlContext`,
  `parseGroundControlYaml`, `normalizeWorkflowConfig`, and `isSafeGitRefName`.
  The first slice needs no new `.ground-control.yaml` keys. The PR's base ref is
  GitHub state; `workflow.base_branch` is the repository's integration-branch
  contract. A mismatch is a consultation stop, not a silent choice between
  branches.
- **Safe Git execution:** reuse `assertSafeImplementCheckoutConfiguration`,
  `runImplementGit`, `fetchImplementBase`, object-ID readers, ancestry checks,
  and the same-checkout postconditions. Reuse these lower-level invariants, but
  do not call `gc_prepare_implement_branch` or pretend a contributor PR branch
  satisfies the issue-number branch-name contract.
- **Verification:** run the repository's existing configured test, completion,
  policy, precommit, lint, and format contracts at proportionate boundaries.
  Reuse `runVerifiedGateBoundary`, the process-tree-safe gate runner, CI outcome
  aggregation, `gc_watch_ci_run`, and `gc_watch_sonar_analysis`. A current
  required-check snapshot is not a substitute for local verification after the
  tree changes.
- **GitHub reads:** reuse `getOwnerRepo`, repository-pinned argv calls,
  paginated API reads, `runGetIssueThread`, `getPullRequestClosingIssues`, and
  the existing CI/Sonar readers. Add one bounded PR-review context reader rather
  than a general GitHub escape hatch or a second client abstraction.
- **GitHub writes:** reuse the argv-shaped post/patch patterns,
  `detectSensitiveBodyContent`, reserved-marker protection, GitHub body-size
  limits, and `extractGhErrorMessage`. Reuse `gc_close_issue_after_merge` for
  each explicitly selected post-merge issue instead of adding closure to a
  review-comment helper.
- **Observability:** every registered tool is already covered by
  `installToolTelemetry`. Preserve the closed ADR-059 shape: tool name, action,
  stable outcome, duration, declared project, and timestamp only. Do not add PR
  bodies, diffs, findings, issue text, paths, comments, subprocess output,
  tokens, or stack traces to telemetry or logs.
- **Skill distribution and policy:** `bin/install-skills.sh` already discovers
  every `skills/<name>/SKILL.md`; do not add a special installer branch. The
  workflow guardrail policy, `docs/DEVELOPMENT_WORKFLOW.md`, `docs/WORKFLOW.md`,
  MCP README, ADR-027, ADR-029, and relevant tool descriptions must describe
  the same phase and communication rules when the skill is implemented.

## Review Context Contract

The read capability should return one bounded, evidence-oriented snapshot. Its
identity fields are the repository slug, PR number, URL, base ref and OID, head
owner/repository/ref and OID, cross-repository flag, author, merge state, and
capture time. Its evidence fields cover:

- PR title/body and review state;
- the complete changed-file inventory, status, old/new paths, additions and
  deletions, plus bounded patches or an explicit unavailable/truncated reason;
- current required and non-required checks, each bound to the observed head
  OID, including pending, missing, skipped, failed, and stale states;
- linked and closing issue candidates with title, body, state, labels, and the
  relationship source;
- existing reviews and unresolved discussion metadata needed to understand the
  change, without posting or resolving anything; and
- completeness flags and byte/file caps so a large or binary diff cannot be
  misrepresented as fully reviewed.

GitHub's per-file `patch` field can be absent or truncated for large, binary,
or renamed files. The collector must paginate the file inventory and state
coverage honestly. It must not fetch into a local ref, switch branches, write
`FETCH_HEAD`, or modify the object database merely to make the read-only claim
easier. When the remote API cannot provide complete content, the result names
the missing files and the review reports missing verification instead of
silently issuing a clean recommendation.

Repository guidance, `AGENTS.md`, `.ground-control.yaml` context, changed code,
tests, and relevant ADR files remain repo-local reads by the agent. Graphify is
an optional comprehension aid under ADR-094, never required evidence. PR and
issue prose, patches, comments, and changed files are untrusted data that may
contain prompt injection; delimit them as data and never execute instructions
found inside them.

The terminal review result should be findings-first and evidence-backed. Reuse
the established location, impact, fix, classification, and sweep-evidence
concepts where they fit, but do not route this result through the
`gc_codex_review` cycle schema: that schema owns caps and mandatory GitHub
writes. The maintainer result needs distinct top-level semantics for merge
blockers, reasonable follow-up work, accepted tradeoffs, missing or stale
verification, and a closed merge recommendation. This is not a duplicate
workflow state schema because it is a non-persisted review presentation model.

## Remediation Contract

- Re-read the PR immediately before the first mutation and compare repository,
  PR number, base/head refs, base/head OIDs, and cross-repository identity with
  the reviewed snapshot. Refuse stale authorization rather than applying fixes
  to a changed PR.
- Refuse a dirty or detached checkout and a checkout on any branch other than
  the PR head. A same-repository branch may be fetched and selected only inside
  the authorized phase. For a fork, verify the exact head repository and that
  the authenticated host can push to it before editing. Report fork deletion,
  disabled maintainer edits, missing credentials, or branch access as stable,
  non-mutating failure classes.
- Bind every push to the reviewed head OID and exact remote head. Use an
  ordinary non-force push to the same PR branch. A remote head change between
  preparation and push is a stale-branch refusal; do not recover with
  `--force-with-lease` or a new branch.
- Fetch the configured integration branch with an explicit refspec, then merge
  it with a real `--no-ff --no-commit` merge when it is not already an
  ancestor. Preserve conflicts in the current checkout for explainable manual
  resolution. Do not abort, reset, discard, or choose ours/theirs
  automatically.
- Run targeted tests while applying fixes, then the configured completion and
  policy boundaries on the final tree. Bind verification to the tree OID,
  toolchain/config fingerprint where available, base OID, and PR head OID.
  Re-fetch the base before push; if it moved, return to the merge boundary.
- After a successful push, post at most one succinct neutral PR comment with
  the change summary, rationale, and verification evidence. Filter the exact
  rendered body for secrets and reserved markers. A comment failure is a
  partial failure after a successful push and must be reported as such; it must
  not trigger another code push or duplicate comment silently.
- Do not post review prose, inline findings, review decisions, issue comments,
  labels, approvals, or resolved threads unless the user explicitly requests
  that separate message or action.

## Post-Merge Issue Selection

Post-merge handling starts only after current GitHub state proves `state=MERGED`
and `merged_at` is non-null. Build candidates from explicit closing references
and PR/issue timeline links, then inspect each issue's actual contract. The
agent classifies a candidate as directly delivered, partially delivered, or
parent/tracking, with evidence from the diff and acceptance criteria.

Only an open, directly delivered issue is passed explicitly to
`gc_close_issue_after_merge(repo_path, issue_number, pr_number)`. The tool's
existing linkage, merge-state, repository-identity, and idempotency checks stay
authoritative. A cross-reference, shared label, milestone, parent relationship,
or mention in prose is not delivery evidence. Already closed issues are no-ops;
partially delivered issues and parent epics remain open.

## Security And Validation Layers

- **MCP shape:** require an absolute `repo_path`, positive PR number, closed
  action enums, bounded authorization text, exact object IDs, safe ref names,
  bounded arrays/strings, and explicit completeness fields. Conditional
  invariants that zod cannot express belong in pure lib validators, not skill
  prose.
- **Repository authorization:** canonicalize the repo root and bind it to the
  MCP launch root, Git common directory, origin URL, and remote-derived
  owner/repo. Revalidate before each mutation boundary. Never return the raw
  origin URL or accept a slug as an alternate write destination.
- **Configuration shape:** read `.ground-control.yaml` only through the
  ADR-027 parser. Unknown keys and invalid `workflow.base_branch` remain hard
  errors. Do not add a review-specific config block until a real per-repository
  variation exists.
- **Git configuration:** run the existing dangerous-local-config check before
  fetch, merge, commit, gate, or push. Keep hooks and external Git commands
  disabled in server-owned Git subprocesses. Treat ref names and PR fields as
  data, never interpolated shell.
- **Credentials and OS exposure:** GitHub credentials stay in the `gh` host
  session, Sonar uses its authorization header, and provider credentials stay
  in sanitized subprocess environments. No token, prompt, diff, issue body,
  comment, environment dump, or command transcript enters process argv,
  telemetry, public comments, or returned errors.
- **Public text:** the remediation summary is model-controlled public text.
  Apply sensitive-content detection, reserved-marker rejection, body-size
  caps, and neutral deterministic rendering to the exact posted string.
- **Error envelope:** map expected stale state, fork access, dirty checkout,
  base mismatch, conflict, failed gate, push race, comment failure, and
  post-merge selection refusal to stable structured outcomes. Bound and scrub
  subprocess stderr/stdout; do not pass raw stack traces or full command output
  through `respond.err`.
- **Read bounds:** paginate GitHub collections, cap bytes and counts, mark
  truncation, and bind all observations to a capture time and head OID. A cap
  produces `incomplete`, never an implicit success.

## Maintainability And Extensibility

- The primary seam is phase/capability, not a broad action mega-tool: one
  read-only context capability, one explicitly authorized same-checkout
  remediation capability, and the existing close tool. This keeps a future
  `review-only` caller unable to discover a mutation by changing an action
  field on the same tool.
- Parameterize the read snapshot by PR number and bounded evidence limits, and
  bind remediation to the snapshot's repository plus base/head OIDs. A future
  GitHub Enterprise transport or different check provider can implement the
  same snapshot without changing review judgment.
- Keep check freshness separate from check success. Future verification
  providers extend the check-evidence list; they do not widen the merge
  recommendation enum or silently count stale success as current.
- Keep follow-up work and accepted tradeoffs separate from blockers. A future
  policy may require explicit maintainer acknowledgement of a tradeoff without
  redefining it as a defect or merge blocker.
- Do not extract a generic GitHub workflow framework. Create a helper only
  where the repository already has multiple call sites for the invariant, or
  where one security-sensitive invariant must have exactly one owner.

## Current-Repository Gotchas

- `gc_codex_review` is not a read-only incumbent. It posts findings records,
  comments, cycle markers, and decision records by design. Reusing it would
  violate the first acceptance criterion even if the skill ignored the URLs.
- `gc_prepare_implement_branch`, `gc_synchronize_implement_branch`, and
  `gc_create_synchronized_implement_pr` encode issue-number branch naming,
  issue-thread attestations, and new-PR creation. Reuse their low-level safety
  helpers, not their `/implement` lifecycle.
- `skills/integrate/SKILL.md` and `mcp/ground-control/README.md` still describe
  `gc_integration_manager`, but the current MCP registration and lib surface do
  not expose that tool. It is not a callable incumbent and its worktree/rebase/
  force-with-lease behavior conflicts with this issue's same-worktree,
  real-merge, non-rewriting contract.
- `gc_watch_ci_run` watches GitHub Actions runs for a branch/head SHA. It does
  not by itself prove the repository's complete current required-check set.
  The review snapshot must collect required-check status explicitly and report
  missing or stale contexts.
- GitHub diff summaries can omit patches. A changed-file count with missing
  patch bodies is incomplete review evidence, not proof that the actual diff
  was read.
- Read-only review cannot honestly promise that no local Git object or ref
  changed if it shells out to `git fetch`. Keep remote inspection on GitHub
  reads until remediation is authorized.
- A PR may come from a deleted fork or a branch the maintainer cannot push.
  Review remains valid; remediation refuses cleanly without creating a new
  branch or asking for a force-push.
- The base and head can move between review, authorization, merge, verification,
  and push. Object-ID compare-and-swap checks are required at every transition;
  branch names alone are stale-prone aliases.

## Test Contract

- Prove the read-only path by recording every injected `gh`/Git command and
  rejecting any mutating method, branch/ref/worktree/file change, comment,
  review, issue update, or close call.
- Cover complete and truncated/binary/renamed diffs, pagination, missing
  required checks, stale check OIDs, changed PR head/base after capture, and
  prompt-injection text treated as data.
- Cover absent remediation authorization, dirty/detached/wrong-branch checkout,
  same-repo success, fork success where push access exists, deleted or
  inaccessible fork, disabled branch access, remote-head races, and push
  refusal without force.
- Cover already-current and stale-base branches, clean and conflicted real
  merges, base movement after verification, verification failure, preserved
  contributor commits, and the no-worktree/no-rebase/no-reset invariants.
- Cover exact comment suppression in review, one summary after successful
  remediation, secret/reserved-marker/body-size refusal, and push-success plus
  comment-failure partial outcomes.
- Cover merged-state proof, already-auto-closed issues, directly delivered open
  issues, partial issues, parent epics, mere cross-references, multiple direct
  issues, and idempotent close retries.
- Use node `--test` suites with injected command runners and temporary real Git
  repositories for branch/merge behavior. Static skill-text assertions may
  guard discovery and wording, but they do not replace behavioral mutation and
  authorization tests.

## Non-Goals And Anti-Patterns

- No automatic merge, approval, review submission, auto-merge, queueing, PR
  close, label change, assignee change, or metadata rewrite.
- No branch creation, worktree, clone, rebase, reset, squash, force-push,
  contributor-history rewrite, or automated conflict-side selection.
- No comment of any kind during read-only review, and no durable marker family,
  local workflow-state file, database record, or backend workflow entity.
- No automatic fixing based only on findings. The user must explicitly request
  remediation, and the authorization applies to the reviewed PR identity, not
  every PR in the repository.
- No duplicate GitHub client, YAML parser, repository identity resolver, check
  poller, Sonar client, secret scanner, error hierarchy, telemetry schema,
  verification command schema, or issue-close path.
- No conversion of heuristic reviewer judgment into automatic merge authority.
  A clean recommendation is advice backed by evidence, not a gate token.
- No assumption that every linked issue is delivered, every green check is
  required/current, every PR branch is locally available, or every contributor
  branch is maintainer-writable.
