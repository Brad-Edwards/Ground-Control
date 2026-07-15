# Base-to-Feature Merge Guard Preflight

Issue #1382 authorizes one narrow local Git operation: merge the current
development/integration base into a non-protected working branch so an open PR
can be kept current. It does not authorize merging or closing a pull request,
writing a protected branch, or rewriting branch history.

This note is architecture preflight guidance only. It does not change the hook,
its tests, host-installed copies, or workflow behavior.

## Architectural Boundaries

- Keep `.claude/hooks/git-merge-guard.py` as the single decision point for
  direct Claude Bash invocations. Extend its existing command normalization,
  wrapper/global-option handling, and `git`/`gh` dispatch; do not add a second
  merge-command scanner or a skill-prose allowlist.
- Model the exception as **base source -> working destination**. A local merge
  commit on a feature branch is branch maintenance, not a pull-request merge.
  `gh pr merge` remains blocked, and ADR-029's integration-manager carve-out
  remains the only automated PR-merge path.
- Preserve the real-merge workflow. The permitted operation incorporates
  `origin/dev` into the feature branch and may be followed by ordinary conflict
  resolution plus `git commit`; it must not redirect the agent to rebase,
  reset, force-push, squash, or discard one side of the merge.
- Keep the hook self-contained because
  `scripts/bootstrap-claude-workflow.sh` installs it as a real copy under
  `~/.claude/hooks/`. Runtime correctness must not depend on importing code
  from whichever Ground Control worktree happens to be checked out.
- Do not parse `.ground-control.yaml` in the hook. ADR-027 makes
  `gc_get_repo_ground_control_context` the only parser boundary for that file.
  Issue #1382 does not justify a second YAML parser or a shadow merge-policy
  schema. For this repository, `dev` and `origin/dev` are already the declared
  integration branch and canonical remote-tracking source in `CONTRIBUTING.md`
  and the workflow conventions.
- Use `.github/branch-protection-baseline.json` as the existing versioned
  authority for protected destination names. Its `branches` keys already name
  `main` and `dev` and can name another protected branch without adding a
  hook-local duplicate list. Read the baseline blob from the resolved
  integration ref, not from the mutable feature-branch working tree; otherwise
  a feature diff could weaken its own guard before review. A missing,
  malformed, unreadable, or invalid baseline must make a candidate merge deny,
  not fall back to an incomplete built-in set.
- Reconcile the durable workflow language before the behavior ships.
  ADR-029's 2026-05-26 amendment and `docs/WORKFLOW.md` currently say that all
  agent-side `git merge` calls remain forbidden, while
  `docs/DEVELOPMENT_WORKFLOW.md` says the user owns every actual merge. They
  must distinguish the newly authorized base-to-feature maintenance merge from
  protected-branch and pull-request merges; do not leave policy semantics only
  in hook comments or tests.

The backend `api/ -> domain/ <- infrastructure/` boundary is not crossed. No
controller, DTO, service, aggregate, repository, database migration, frontend
schema, or MCP tool belongs in this change.

## Security And Validation Layers

- **PreToolUse input shape:** retain the existing JSON-stdin contract and
  inspect only `tool_input.command`. A candidate merge with malformed JSON, a
  non-string command, an unparseable shell command, or missing repository
  context must deny. Non-merge commands should retain their current behavior.
- **Shell/argv shape:** reuse `normalize_operators`, `split_segments`,
  `strip_wrappers`, and the existing Git global-option handling. Authorize only
  a directly inspectable, single merge invocation. Chained commands that can
  change directory or repository state (`cd ... &&`, subshells, multiple merge
  segments) are ambiguous at hook time and must deny; the fetch and merge can
  be separate Bash calls.
- **Repository context:** query Git with fixed argv arrays and a bounded
  timeout, never `shell=True`. Determine the actual current local branch with
  `git symbolic-ref`; detached HEAD, an unborn branch, an inaccessible
  worktree, conflicting `-C`/`--git-dir` context, or an in-progress ambiguous
  merge state must deny.
- **Destination policy:** compare the normalized current branch name exactly
  against every configured protected branch. Substrings are not identities:
  `feature/dev-rebase` is not `dev`, while `refs/heads/dev` is. Never infer a
  safe destination from the command text because `git merge` does not name its
  destination.
- **Source identity:** parse exactly one source operand and resolve its
  symbolic full ref. The permitted source is the exact remote-tracking ref
  `refs/remotes/origin/dev`; do not authorize by commit OID alone. A tag, SHA,
  local feature ref, multiple merge heads, missing/unresolvable ref, or a name
  that merely contains `dev` must deny even when it currently points at the
  same commit as `origin/dev`.
- **Merge option policy:** use a closed set of options needed by the normal
  base-update merge (for example `--no-edit` and `--no-ff`) and reject unknown
  or semantics-changing modes. In particular, `--squash`, `--strategy=ours`,
  `-s ours`, `--allow-unrelated-histories`, and `--no-verify` do not satisfy a
  real, policy-gated base merge. Stateful source-less modes such as
  `--continue`, `--abort`, and `--quit` stay denied unless they receive a
  separate `MERGE_HEAD`-based validator with the same source and destination
  guarantees; ordinary `git commit` already completes a resolved conflict.
- **Existing destructive-operation gates:** keep unconditional denial of
  `gh pr merge`, `git reset --hard`, plain `git push --force`/`-f`, protected
  force-with-lease destinations, wildcard refspecs, and ambiguous `HEAD`
  destinations. The merge exception must not share a broad "feature branch is
  safe" predicate with push authorization because the commands have different
  source and destination semantics.
- **Config validation:** treat the branch-protection blob as untrusted local
  input even though it is read from the integration ref. Require a JSON object
  with a non-empty `branches` object, require `main` and `dev`, and require
  safe, normalized local branch-name keys; reject control characters,
  option-shaped names, invalid Git refs, and schema ambiguity. The existing
  `run_ci_strictness_contract` remains the repository-level check that `main`
  and `dev` are present and PR-protected.
- **Secret and OS exposure:** the hook needs no token, network request, GitHub
  call, `.env`, or secret file. Do not print the full command or environment in
  denials because unrelated argv may contain sensitive data. Fixed Git query
  argv and concise source/destination reason text are sufficient.
- **Error surface:** this is not an HTTP or MCP boundary, so
  `ErrorResponse`, `GlobalExceptionHandler`, and Ground Control exception
  subclasses do not apply. Reuse the hook's established contract: no output on
  allow, a concise stderr denial, and exit code `2` on policy refusal.
- **Observability:** do not introduce telemetry or a second logging stack for a
  synchronous local guard. Tests and the denial reason are the audit surface;
  allowed commands should remain quiet, and commands/config contents should
  not be persisted.

The hook is a pre-execution policy control, not an OS sandbox. Its existing
lexical boundary does not prove safety for arbitrary shell functions, Git
aliases, nested interpreters, or another process racing the worktree between
check and execution. Do not claim those surfaces are secured by this issue. At
the same time, do not weaken the direct `git`/`gh` coverage already tested; any
newly discovered direct-command bypass belongs in the same parser and test
matrix.

## Maintainability And Extensibility

- Keep one pure merge-policy decision seam whose inputs are the parsed merge
  operands, resolved source ref, current branch, integration source, and
  protected-destination set. Repository probing and denial rendering should
  stay at the edge. This permits another protected branch to be added through
  the existing branch-protection baseline without rewriting command parsing.
- Keep the integration source explicit rather than guessing from the only
  protected branch that is not `main`. If a later cross-repository change must
  configure a base other than `origin/dev`, the value must arrive through a
  canonical, validated delivery path compatible with ADR-027; do not make the
  hook read `.ground-control.yaml` ad hoc in anticipation of that change.
- Do not extract a generic shell parser or policy framework. The incumbent
  helpers already cover the call sites, and a new abstraction would exceed the
  issue's narrow policy delta.
- Keep the checked-in hook, `tools/tests/test_git_merge_guard.py`, bootstrap
  documentation, workflow docs, ADR-029, and the host-copy instructions in
  sync. The implementation must re-run
  `scripts/bootstrap-claude-workflow.sh` to activate the checked-in change on
  the host; a passing repo test with a stale `~/.claude/hooks/` copy is not an
  operational rollout.

## Test Contract

- Exercise the hook as a subprocess against temporary real Git repositories;
  mocked branch strings cannot prove symbolic-ref and source-resolution
  behavior.
- Add a positive case for an exact `origin/dev` source while checked out on a
  feature branch, including the supported normal merge flags.
- Add a negative case for every protected destination from a fixture baseline:
  `main`, `dev`, and at least one additional configured branch.
- Pin fail-closed cases for detached HEAD, malformed/missing protection config,
  missing or ambiguous source, multiple sources, local `dev`, tag/SHA aliases,
  same-OID non-base refs, option-shaped refs, chained/cwd-changing commands,
  unsupported merge modes, and unresolved refs.
- Retain the current regression matrix for PR merge, reset, force push,
  wrappers, Git/GitHub global options, shell operators, wildcard refspecs, and
  branch names that merely contain `dev`.

These are stdlib Python hook tests. `@WebMvcTest`, Testcontainers, Spring
validation, Zod, SLF4J, ActorHolder, persistence repositories, and HTTP error
envelopes are intentionally out of scope.

## Non-Goals

- No pull-request merge, close, approval, auto-merge, merge queue, or protected
  branch write.
- No rebase, reset, force-push, squash, history rewrite, conflict auto-choice,
  or bypass of commit hooks.
- No fetch or freshness proof for `origin/dev`; the workflow fetches before a
  separate merge call, and the guard remains read-only.
- No new `.ground-control.yaml` field, merge-policy file, environment-variable
  schema, MCP tool, backend API, database state, or durable workflow marker.
- No broad shell-security rewrite, Git alias framework, or replacement for
  GitHub branch protection and required status checks.
- No host bootstrap execution during architecture preflight; implementation
  activates the copy only after the hook and tests change together.

## Post-review revisions (issue #1382 codex security review)

The pre-push codex security review corrected two points of the guidance above;
the shipped hook reflects the corrections:

- **Protected-destination authority is a hardcoded constant, not the
  baseline-at-`origin/dev`.** This note recommended reading the protected-branch
  set from `.github/branch-protection-baseline.json` at the integration ref to
  keep it config-driven. That ref (`refs/remotes/origin/*`) is writable locally
  with a single `git update-ref`, so an agent could repoint it to a baseline that
  keeps `main`/`dev` but drops another protected branch, then merge into that
  branch. The protected-destination authority must be non-agent-writable, so the
  hook uses the hardcoded `PROTECTED_BRANCHES = {main, dev}` constant it already
  used for push refspecs. Adding a protected branch is a reviewed change to that
  constant.
- **Additional validation-boundary hardening the review surfaced:** reject shell
  expansion / substitution (`$`, backtick, `<`, `>`) anywhere in a candidate
  merge command - it stays active even inside a double-quoted `-m` value and would
  run arbitrary shell (including a `git checkout` to a protected branch) after the
  hook returns success; resolve the current branch from the FULL symbolic ref
  (`symbolic-ref HEAD`, not `--short`) so a colliding tag cannot disguise a
  protected destination; and deny when `branch.<destination>.mergeOptions` is
  configured, since Git applies it even when the prohibited mode never appears on
  the inspected command line.
