# Codex Manifest Review Evidence Preflight

Issue #1414 fixes a trust-boundary failure in `gc_codex_review`: an oversized
diff currently becomes a numstat manifest plus a prompt asking Codex to fetch
file diffs, but nothing proves that either reviewer fetched one. A
manifest-only `ship` is then flattened into the same clean cycle envelope as a
full-diff review.

This note is architecture guidance only. It does not implement the issue.

## Binding Design

- Keep `diff_mode` as the transport fact already selected by
  `selectDiffMode`: `inline` when the complete diff fits the byte budget and
  `manifest` when it does not. Derive it inside the MCP server; do not accept a
  caller-supplied mode.
- A manifest is routing metadata, not review evidence. `runCodexReview` must
  not return `proceed_clean`, write a cycle marker, or let the cycle wrapper
  post a clean decision record from manifest-only reviewer prose.
- For manifest mode, split the already-computed authoritative diff into
  bounded inline review slices and run both existing reviewers over every
  slice as one logical review cycle. Aggregate findings, architectural reads,
  and notes only after every required slice has produced a valid structured
  envelope. Do not delegate completeness to optional reviewer shell calls or
  reviewer-authored claims that files were read.
- Preserve the distinction between transport and coverage:
  `diff_mode = "manifest"` says the complete diff did not fit one prompt;
  a separate bounded `review_coverage` summary says how it was reviewed, for
  example `strategy`, `chunks_total`, `chunks_completed`, `files_total`, and
  `files_covered`. Do not rename manifest mode to inline merely because each
  generated slice is inline.
- Surface `diff_mode` and the bounded coverage summary on the direct
  `gc_codex_review` result, the compact `gc_codex_review_cycle` envelope, and
  the durable findings record. Raw diffs, command traces, prompts, and child
  output do not belong in the compact envelope.
- A slice engine failure, invalid reviewer envelope, incomplete slice set, or
  unknown coverage is a review boundary failure. Return `ok: false`,
  `status: "post_failed"` at the cycle boundary, a stable specific `error`,
  and a retry-oriented `next_action`. No findings record, decision record, or
  cycle marker may be written before complete coverage is established; the
  failed attempt must not spend the issue's review-cycle cap.
- Aggregate verdicts deterministically in the MCP layer. Any blocking finding
  prevents an aggregate clean result, and any structural blocker preserves
  the existing `don't-ship` semantics. Reuse the current finding validation,
  verdict/blocking consistency, deduplication, and architectural-read merge
  logic rather than defining a second review schema.
- `gc_review_cap_disposition` must derive the current post-fix `diff_mode`
  server-side with the same selector and include it in its existing
  `signals_snapshot`. Manifest or unknown coverage must never be silently
  treated as the same low-risk signal as a fully covered inline diff.

The slice byte budget belongs at the existing
`GC_CODEX_REVIEW_MAX_DIFF_BYTES` seam. Do not add a second YAML key or
environment variable for the same limit without an independently justified
variation. A future review strategy can vary chunk concurrency or selection
policy behind the coverage shape without changing the meaning of
`diff_mode`, the cap counter, or the durable record.

## Diff Correctness Guardrails

- `computeReviewDiff` remains the single source for review content, manifest,
  and resolved base-ref metadata. Extend its return shape instead of
  recomputing competing diffs in prompt builders, cycle wrappers, or cap
  disposition.
- Uncommitted review must cover the same tree the workflow claims to review:
  staged, unstaged, and untracked files. The current full diff and numstat omit
  untracked files even though Step 6.5 and the prompt claim they are included.
  Reuse the repository's existing
  `git ls-files --others --exclude-standard` pattern and represent new files as
  additions in both review content and manifest.
- Preserve additions and deletions. Deletion-heavy slices must retain their
  direction; neither prompt text nor aggregation may infer an introduction
  from filenames when numstat and hunks show removal.
- Slice on valid file/hunk boundaries where possible. A single file larger
  than the budget must still be reviewable through bounded hunk slices; it
  must not be skipped, truncated, or downgraded to manifest-only evidence.
  Binary, rename, whitespace-only, unusual filename, and UTF-8 byte-count
  cases need explicit behavior.
- Treat repository paths as untrusted input. Continue lexical containment
  validation and fixed-argv `execFile` Git calls. Do not construct a shell
  command by interpolating a manifest path, and do not ask the reviewer to do
  so as the required completeness mechanism.

## Cross-Cutting Concerns to Reuse

- **Review schema and validation:** `parseCodexReviewEnvelopeTail`,
  `validateReviewEnvelope`, `validateFindingPath`,
  `checkVerdictBlockingConsistency`, `dedupFindings`, and
  `mergeReviewerArchitecturalReads`.
- **Review orchestration:** `computeReviewDiff`, `selectDiffMode`,
  `buildCodexReviewCorePrompt`, `buildCodexSecurityReviewPrompt`,
  `runSingleCodexReview`, `runCodexReview`, and
  `_runReviewCycleShared`. One logical cycle owns all slices; do not create a
  second cap counter or marker family per slice.
- **Compact cycle contract:** `reviewCycleFindings`,
  `summarizeReviewFindings`, `normalizeReviewCycleNextAction`,
  `buildAutoFixDecisionFindings`, and `runPostDecisionRecord`.
- **Durable records and privileged writes:**
  `buildCodexReviewFindingsComments`,
  `postCodexReviewFindingsComment`,
  `postCodexReviewPrePushCycleMarker`,
  `detectSensitiveBodyContent`, marker disarming, GitHub body-size checks, and
  argv-based `gh api` posting in the MCP server.
- **Structured failures:** preserve the established
  `{ok, error, message, next_action, ...}` envelopes. Do not throw expected
  coverage failures, expose stack traces/raw stderr, or introduce a parallel
  exception hierarchy.
- **Async and observability:** reuse `startReviewJob`/`gc_codex_job`, abort
  propagation, the existing Codex timeout, and MCP tool-call telemetry. Add
  bounded mode/coverage fields to results and durable records; never log or
  return raw diff slices or child-process event streams.
- **Configuration boundary:** retain the existing module-level
  `GC_CODEX_REVIEW_MAX_DIFF_BYTES` binding and its documented `0`-disables
  behavior. Do not read `.ground-control.yaml` outside
  `getRepoGroundControlContext`; this issue does not need a new YAML schema.
- **Sibling callers:** `/implement` and `/quickfix --review` consume the same
  cycle wrapper. Post-push direct `gc_codex_review` callers must receive the
  same evidence semantics even though `/implement` no longer drives that path.

No backend controller, DTO, service, repository, persistence, frontend, or
`GlobalExceptionHandler` change is required. If implementation drifts into
those surfaces, it has crossed the intended boundary and needs a new design
decision.

## Security and Runtime Layers

- **MCP input shape:** existing Zod validation continues to own
  `repo_path`, issue/PR identifiers, booleans, and override inputs.
  `diff_mode` and coverage are outputs derived from trusted server execution,
  never caller assertions.
- **Repository containment:** `ensureGitRepo`, resolved repository roots, and
  repo-relative path validation apply before Git reads or finding posts.
- **Prompt-injection boundary:** diff, filenames, and vocabulary remain
  untrusted prompt data. Full coverage is established by server-built slices,
  not imperative text embedded in a manifest or a model claim that it used a
  tool.
- **Subprocess boundary:** prompts and diff slices stay on stdin; do not place
  source content, secrets, or environment values in process argv. Keep the
  Codex child read-only and preserve abort/timeout handling.
- **Secret/publication boundary:** pass every model-derived durable-record
  body through reserved-marker disarming, `detectSensitiveBodyContent`, and
  GitHub body-size enforcement before the first post. Coverage metadata must
  remain bounded and must not carry raw content.
- **GitHub side-effect boundary:** only the MCP server performs `gh api`
  writes. Coverage must be validated before any finding/record/marker write so
  a failed review remains safely retryable and cannot advance workflow state.
- **Error-envelope boundary:** expected coverage/config/parser/posting
  failures return stable structured envelopes and bounded messages. They do
  not leak raw prompts, diffs, command output, environment variables, or
  filesystem details beyond the already-established repo-relative fields.
- **Host/runtime limits:** respect the existing 50 MiB Git diff buffer,
  review timeout, cancellation signal, temp-directory cleanup, sequential vs.
  parallel reviewer setting, and GitHub comment caps. Oversized work may fail
  closed with a retry/escalation signal; it may not fall back to a false clean.

## Verification Guardrails

Tests must pin behavior rather than prompt substrings alone:

- boundary selection at, below, and above the UTF-8 byte cap;
- deterministic full coverage across multiple slices and a single file larger
  than one slice;
- aggregation of clean, finding-bearing, structural-blocker, duplicate, and
  partial-failure slice results;
- no durable record, decision record, or cycle marker when any slice or
  coverage validation fails;
- `diff_mode` and bounded coverage propagation through direct review, cycle
  wrapper, durable record, async poll result, and cap-disposition signals;
- staged, unstaged, untracked, deletion-only, binary, rename, and unusual-path
  cases;
- unchanged inline behavior for below-cap diffs and unchanged cap semantics
  for one logical multi-slice cycle.

Use the existing Node test/shim infrastructure in
`mcp/ground-control/lib.test.js`. Update public MCP descriptions and workflow
docs with the implementation, then run the repo-native `make policy` gate.
Because the implementation changes review-loop and public MCP surfaces, keep
ADR-029, ADR-031, ADR-036, `mcp/ground-control/README.md`,
`docs/WORKFLOW.md`, `docs/DEVELOPMENT_WORKFLOW.md`,
`skills/implement/steps/step-06.5-codex-review.md`,
`skills/implement/steps/_review-loop-rules.md`, and the `/quickfix --review`
contract synchronized where their stated envelope or stopping semantics
intersect.

## Gotchas and Anti-Patterns

- Do not fix this with stronger prompt wording alone.
- Do not accept reviewer prose, `reviewed_paths`, or a manifest-only
  architectural read as proof of diff coverage.
- Do not let a manifest-mode caveat pass because `blocking[]` is empty.
- Do not surface `diff_mode` only on the direct review result and drop it in
  `_runReviewCycleShared`; the compact cycle envelope is the orchestrator
  contract.
- Do not count chunks as review cycles or post per-chunk cycle markers.
- Do not post partial slice findings and then retry the logical cycle; that
  creates duplicate durable state and ambiguous cap ownership.
- Do not duplicate diff acquisition, finding schemas, next-action mapping,
  sensitive-content checks, marker parsers, or GitHub writers.
- Do not make `gc_review_cap_disposition` trust a caller-provided mode or
  coverage claim.
- Do not use the Ground-Control-specific documentation surface classifier as
  a generic security-file classifier for arbitrary reviewed repositories.
- Do not add a new backend endpoint, database record, telemetry schema, or
  workflow DSL for review coverage.

## Non-Goals and Boundaries

- No change to the default review-cycle cap, human override authority, or
  automated over-cap grant ceiling.
- No new reviewer provider, backend API, frontend UI, database table, Temporal
  workflow, or persistent local review cache.
- No restoration of the removed post-push review step.
- No claim that a model's cognition can be proven. The enforceable contract is
  that every authoritative diff slice was supplied to both reviewers and
  every structured result validated before a clean workflow signal.
- No redesign of test-quality review; it shares only the generic cycle wrapper
  and should continue to receive its existing semantics.

## Design Vocabulary That Applies

- **Patterns:** none of `Repository`, `Service+Aggregate`, `Command DTO`, or
  `WebMvcTest controller slice` apply; the intended diff stays in the MCP
  workflow surface.
- **Canonical helper:** argv-based `gh api` posting in
  `mcp/ground-control/lib.js` remains the only privileged GitHub write path.
- **Boundary contract:** `api/ → domain/ ← infrastructure/` is not crossed.
  No backend layer should change.
- **Binding ADRs:** ADR-027 for the MCP/config trust boundary; ADR-029 for the
  issue-thread durable record and marker ordering; ADR-031 for structured
  verdict and stopping semantics; ADR-036 for the cycle wrapper, deterministic
  record rendering, async job model, and telemetry.
- **Anti-recommendations:** do not add prompt-only workflow rules the MCP layer
  cannot enforce; do not invoke `gh`, `git`, or `curl` from agent sandboxes for
  privileged side effects; do not invent a second abstraction/schema below
  the existing review seams; do not add comments that merely restate code.
