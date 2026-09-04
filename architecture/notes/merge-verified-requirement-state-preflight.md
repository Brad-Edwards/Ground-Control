# Merge-Verified Requirement State Preflight

Issue: #1541
Requirement: none

This note records architecture guardrails for moving specs-as-code transitions into the
delivery pull request and verifying them at the immutable merge result. It is guidance,
not an implementation plan.

## Authority And Ordering

- Requirement `status:` and `## Traceability` edits are delivery changes. They must be
  complete before the existing verification, review, publish, and pull-request gates so
  reviewers and CI inspect the same requirement files that will become authoritative.
- Phase E is validation-only for requirement-backed runs. It resolves the merged pull
  request, validates every in-scope requirement at an immutable target-branch commit,
  posts the final report, and closes the issue. It must not edit, commit, or push a
  requirement file after merge.
- A work-already-complete path may avoid a delivery pull request only when an immutable
  target-branch commit already contains the expected status and traceability. If the
  requirement files need any change, those changes still require a reviewed pull request.
- Pre-merge readiness reports proposed state separately from merged authority. A pull
  request head can prove what is proposed; it cannot be described as authoritative before
  merge. Post-merge reports use only values observed at the validated merge revision.
- Requirement-free runs keep their current behavior. An authoritatively empty in-scope UID
  set skips requirement-state validation, while the merge, review, CI, Sonar, content-scrub,
  final-report, and close gates remain unchanged.

## Immutable Requirement Read Boundary

The existing `mcp/ground-control/lib/requirement-files.js` reader remains the canonical
parser. Extend that boundary with a revision parameter or sibling revision-aware entry
point; do not add another frontmatter or traceability parser inside completion logic.
Working-tree callers may retain their current convenience wrapper.

The revision used for completion is resolved server-side from the linked, merged pull
request. It is a full validated commit object id for the merge result or another immutable
commit known to be on the target branch. It is not accepted from the driver, inferred from
`HEAD`, or taken from a mutable branch name. The reader obtains
`docs/requirements/<UID>/requirement.md` at that revision without checking out or modifying
the worktree. Use fixed argv through the MCP server's existing Git/GitHub command boundary;
never shell-interpolate the revision, UID, or path.

For every UID freshly derived from the issue's canonical Requirements section, validation
must distinguish and fail closed on:

- the exact tree path being absent;
- missing or unterminated frontmatter, or malformed governed traceability syntax;
- frontmatter `id` absent or not exactly equal to both the requested UID and folder name;
- a lifecycle status outside the existing closed status vocabulary;
- observed status differing from the validated status intent;
- missing traceability required by the existing reconciliation rules.

Do not let `readRequirementByUid`'s current `id || uid` normalization hide a missing or
mismatched frontmatter id on this path. Expose a structured parse/validation result at the
canonical reader boundary, then let compatibility callers map it to their existing nullable
shape if needed. Keep the JavaScript contract aligned with
`tools/policy/requirement_specs.py`; the policy lint validates the proposed PR tree, while
the completion assertion validates the immutable merged tree. These are complementary
trust boundaries, not two competing schemas.

## Scope, Status, And Traceability Semantics

The completion tool must re-read the issue thread and derive the complete in-scope UID set
with `extractInScopeRequirementUids`. Caller-supplied `requirements[]` is an expectation
input, not authority to add, omit, or substitute a UID. Require an exact one-to-one match
between those expectations and the canonical issue set before reading or posting anything.

Keep proposed and observed values separate:

- `status_intent` is the validated pre-merge proposal and assertion target;
- the immutable requirement record supplies observed status, title, id, and traceability;
- the post-merge final report renders the observed record only after it matches the intent.

Reuse the current reconciliation semantics rather than inventing a second coverage model.
An ACTIVE requirement needs an IMPLEMENTS entry for an artifact of record, plus a TESTS
entry when its IMPLEMENTS targets enter the existing testable-surface predicate. Every
in-scope requirement also needs the current `GITHUB_ISSUE` entry with the workflow-defined
link type; a forward-looking DRAFT requirement uses DOCUMENTS rather than IMPLEMENTS.
Centralize and reuse the existing testable-surface prefixes in `lib/pr-body.js` instead of
copying them into the immutable validator. Human-readable `completion.traceability`
summaries remain report content and must not be parsed or trusted as assertion evidence.

The useful extension seam is the canonical requirement reader's immutable `revision`
parameter plus the existing per-UID completion expectation. The same reader can inspect a
pull-request head for proposed readiness and the merge commit for authority. A later need
for stricter link-target verification can extend the structured per-UID assertion without a
new tool, parser, store, or workflow engine.

## Completion, Closure, And Durable Records

`runAssertCompletion` remains the owner of completion sequencing, and
`gc_implement_mechanical action="finalize"` remains the canonical composition of completion
then close. A requirement validation failure must happen before `runPostFinalReport`; the
failure posts no final marker and `runFinalize` must not call `gc_close_issue_after_merge`.

The canonical PR renderer must use a non-closing issue reference such as `Refs #<number>`.
`Closes #<number>` permits GitHub to close the issue before merged requirement validation,
which bypasses the canonical close invariant. Keep `resolvePrForClose` as the shared
issue-to-PR linkage and merge-state authority, and update its renderer/policy fixtures in
lockstep rather than adding another PR resolver or close path.

Issue-thread history is append-only. Do not edit or delete prior readiness comments, phase
markers, or final reports. A corrected run appends its validated record. Readiness retains
the `ready_for_review` marker; only successful immutable validation may precede the
`gc:final-report` marker and the merge-gated close.

## Cross-Cutting Contracts To Reuse

- **Tool registration:** the existing Zod registration in
  `mcp/ground-control/tools/post-decision-record.js` stays thin. Reuse
  `EXACT_REQUIREMENT_UID_RE`, the closed lifecycle status enum, positive issue/PR numbers,
  and the existing completion DTO instead of defining parallel schemas in the tool and
  mechanical layers.
- **Repository and GitHub identity:** reuse `ensureGitRepo`, `getOwnerRepo`, and
  `resolvePrForClose`. The resolved PR must carry the immutable target revision needed by
  the requirement reader.
- **Requirement parsing:** reuse `requirement-files.js`, its traceability link shape, and
  the exporter/frontmatter contract documented in `docs/requirements/README.md`.
- **Completion composition:** reuse `runAssertCompletion`, `mapCompletion`, `runReadiness`,
  `runFinalize`, and the existing `assertions[]` envelope. Do not create a second finalize
  tool or exception hierarchy.
- **Final report and public-text safety:** reuse `validateFinalReportInput`,
  `buildFinalReport`, `runPostFinalReport`, `detectSensitiveBodyContent`, reserved-marker
  rejection, no-deferral detection, body-size caps, and argv-based issue-comment posting.
- **PR body and close:** reuse `validatePrBodyInput`, `buildPrBody`,
  `checkPrBodyShape`, `gc_create_synchronized_implement_pr`, and
  `gc_close_issue_after_merge`. Change the canonical link token consistently across the
  JavaScript renderer, Python policy, templates, docs, and compose tests.
- **Policy and verification:** keep `run_requirement_specs_frontmatter_check`, the
  implement workflow-contract checks, `make policy`, pre-commit, CI, and Sonar in their
  existing positions. Do not substitute a post-merge active-checkout policy run for the
  immutable assertion.
- **Observability:** retain the existing readiness/finalize lifecycle stations and stable
  mechanical failure envelopes. The immutable revision and bounded per-UID assertion
  outcomes are enough evidence; no new log stream, metric, marker family, or state store is
  needed.

## Security And Failure Boundaries

- **MCP shape gate:** reject invalid issue/PR numbers, duplicate or invalid UIDs, unknown
  status values, incomplete expectation sets, and malformed completion input before GitHub
  or final-report side effects.
- **Issue authorization gate:** a fresh issue-thread read determines the in-scope UIDs.
  Syntax validation alone does not authorize a caller-selected requirement.
- **Repository/path gate:** bind reads to the authorized repository. Construct only the
  exact UID path after anchored UID validation; reject absolute paths, traversal, alternate
  filenames, symlink/worktree fallbacks, and mutable refs.
- **Revision gate:** obtain the object id from the linked merged PR, validate its full hash
  shape, and read the tree object without checkout. A local `HEAD`, feature branch, remote
  tracking name, or caller-provided hash is not authority.
- **Process and credential gate:** GitHub credentials remain in the MCP host's existing
  authentication environment. Pass no token, requirement content, or untrusted command
  fragment in argv. Do not introduce an environment/config binding for this feature.
- **Content gate:** parse requirement content internally and return only bounded metadata.
  Never return or log the statement, rationale, raw frontmatter, raw traceability lines,
  command stdout/stderr, or arbitrary parser exceptions.
- **Error envelope:** use stable failure codes and a per-UID safe observation such as
  record state, id-match boolean, recognized status, and link-type counts. Include expected
  status/required link types and the immutable revision. Do not place arbitrary observed
  scalar values in extra envelope fields, because the existing `failure()` helper scrubs
  its message but does not recursively scrub extras.
- **Side-effect gate:** validate all UIDs before posting the final report. On any failure,
  return one bounded aggregate failure, post no completion marker, and do not close the
  issue.

There is no backend, REST controller, database, role, bearer-token filter, persistence,
environment-schema, or OS service exposure in this change. Adding one would cross the
current architecture boundary rather than harden it.

## Whole-Repository Surfaces

The binding implementation surface spans the canonical implement skill and Steps 15, 16,
17, 20, and PR-body step 9; `docs/WORKFLOW.md` and
`docs/DEVELOPMENT_WORKFLOW.md`; ADR-029 and ADR-093; the MCP completion, requirement-reader,
PR-renderer, close-resolver, and tool-schema modules; the PR template and JavaScript/Python
PR-body policy; requirement-spec policy; and their Node/Python regression tests. Tool
descriptions in `mcp/ground-control/README.md` and registrations must describe the same
authority model. No backend, frontend, deployment, or Graphify surface is in scope.

## Gotchas And Anti-Patterns

- Do not leave Steps 15/16 after merge and try to push a second bookkeeping commit.
- Do not validate the active checkout, `HEAD`, a mutable branch, or cached bootstrap record.
- Do not let a caller omit one issue UID, lower the expected status, or supply an unrelated
  merged PR/revision to make completion pass.
- Do not validate one requirement and stop successfully; collect safe failures for every
  in-scope UID while withholding all side effects.
- Do not parse human report summaries to recover machine expectations, and do not duplicate
  the frontmatter, traceability, UID, lifecycle, or testable-surface schema.
- Do not post the final report before validation, close through direct `gh`, or retain
  automatic-closing PR metadata.
- Do not rewrite historical comments or markers. Corrections are additive.
- Do not add prompt-only guarantees, a new marker family, local ledger, backend record,
  generic repository abstraction, or exception hierarchy.

## Non-Goals

- No implementation of issue #1541 in this note.
- No change to requirement statements, lifecycle vocabulary, or traceability line format.
- No redesign of review, CI, Sonar, execution-obligation, or branch-synchronization gates.
- No automatic PR merge, cross-repository requirement read, or Graphify dependency.
- No change to `/quickfix` or other requirement-free behavior.

## Design Vocabulary That Applies

- **Patterns:** Tool registration; Requirement file reader; Issue-thread record.
- **Canonical helpers:** argv-based `gh api` posting in `mcp/ground-control/lib.js`;
  `requirement-files.js` reader.
- **Boundary contract:** the MCP server is the only running service and owns every
  privileged `gh`/`git` side effect; requirements and ADRs remain repo-local reviewed files.
- **Binding ADRs:** ADR-027, ADR-029, and ADR-093.
- **Anti-recommendations:** do not introduce abstractions below three call sites; do not add
  skill prose that the MCP tools cannot enforce; do not add comments that restate code; do
  not invoke `gh`, `git`, or `curl` from agent sandboxes.
