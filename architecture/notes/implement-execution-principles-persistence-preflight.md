# Implement Execution Principles and Persistence Preflight

Issue #1416 makes instruction fidelity, same-checkout execution, persistence,
and problem ownership part of the `/implement` contract. This note sets the
architecture boundaries for that work. It does not implement the workflow
change.

## Architecture Decisions

### One canonical principles contract

Create one short, agent-neutral contract at
`skills/implement/_development-principles.md`. The canonical
`skills/implement/SKILL.md` must load it before configuration lookup, route
resolution, issue resolution, branch handling, or delegation.

The parent owns an immutable execution-contract object containing the contract
schema/version, source path, content digest, canonical invocation root, and
checkout mode. The parent passes the principles verbatim in every delegated
step prompt and carries the execution-contract object in cached state. A
subagent result may add step outputs, but it must not replace the contract,
invocation root, or checkout mode.

This is workflow prose packaging, not a new workflow language or configuration
source. `.ground-control.yaml` and `gc_get_repo_ground_control_context` remain
the repository configuration contract under ADR-027. The principles contract
must not add a second routing matrix, phase model, or completion definition.

Parent-only drivers, including Codex and Cursor CLI, still load the contract
before executing Step 1 inline. The Cursor discovery wrapper remains a pointer
to the canonical skill. `bin/install-skills.sh` remains the distribution path
for the complete skill directory, including the principles file.

### Same-checkout branch handling is a tool boundary

Step 1 must stop invoking `gh issue develop --checkout` and multi-command
branch-repair recipes directly. Put the `/implement` branch mutation behind one
MCP operation that uses the existing `ensureGitRepo`, `getOwnerRepo`,
safe-ref validation, and `execFile` argv conventions.

The operation receives the invocation root captured before any routing or
issue work. It canonicalizes that path, records the initial top-level and Git
directory identities, performs only the closed branch-resolution argv
sequence, and verifies afterward that:

- the canonical `git rev-parse --show-toplevel` result equals the invocation
  root;
- the active Git directory still belongs to that checkout;
- the checked-out branch satisfies the existing issue-prefix, character, and
  length rules; and
- the checkout still resolves to the same origin repository.

Any mismatch returns a structured failure before later steps run. Do not
silently follow the new working directory, repair by moving files, or treat a
shared Git common directory as proof that the worktree root is unchanged.
Linked worktrees share a common directory, so top-level identity is the
dispositive check.

The default checkout mode is `same_checkout`. Do not add an
`allow_worktree: true` boolean. A worktree exception requires a per-run,
durable user authorization and a lifecycle owner that guarantees cleanup on
success, failure, and cancellation. The current Markdown-driven
`/implement` run has no process that spans all those exits. Until such an
owner exists, the tool must fail closed rather than create an authorized
worktree. A future typed `checkout_mode` seam may add
`authorized_worktree` only together with marker-backed authorization and a
cleanup lease/activity. The integration manager's existing isolated-worktree
contract is unchanged.

Agent or runtime hooks that reject `git worktree add` are defense in depth.
They cannot be the canonical enforcement because Claude Code, Codex, and
Cursor do not share one hook mechanism. Repo policy must also reject direct
worktree-creation commands in `/implement` workflow surfaces and require the
branch tool contract.

### Discovered problems are durable obligations

Keep these concepts separate:

- A review finding belongs to the existing
  `gc:decision-record` schema and its `fix | wontfix | not-applicable`
  dispositions.
- A clause mapping proves requirement or issue acceptance coverage.
- An execution obligation is a real defect, failing check, security concern,
  broken workflow, or material quality problem discovered at any step,
  regardless of provenance.
- An escalation pauses an open obligation to obtain authority or judgment. It
  does not resolve, defer, or remove the obligation.

Cached `open_obligations` may help the next step, but cached state is not
durable evidence. Record obligation open, escalation, and resolution events on
the GitHub issue thread with one versioned marker family and deterministic
renderer. Build it on the existing phase-marker, reserved-marker,
sensitive-content, body-size, origin-repository, and argv-based posting
helpers. Do not overload review decision records or telemetry with this state.

An escalation record must contain the observed state, evidence, impact,
current obligation, and a concrete decision request. A resolution must contain
the corrective action and verification, or an existing valid terminal
disposition:

- `fix` means repaired and verified in the current work;
- `wontfix` requires explicit user authorization and is not a synonym for
  follow-up work; and
- `not-applicable` means the reported condition is factually false or does not
  apply to this codebase. A real problem outside the initiating diff is not
  `not-applicable`.

`gc_assert_completion` must re-read the issue-thread obligation markers and
refuse both pre-merge readiness and post-merge completion while an obligation
is open. Caller-supplied cached arrays or summaries are not proof that the
ledger is clear.

### Terminal states stay narrow

Preparatory work, a progress update, plan publication, instruction loading,
and acknowledgment of a user correction are not successful terminal states.
After a correction, the parent updates the active contract/context and resumes
the original operation unless a documented escalation class applies.

The allowed pause classes are an explicit workflow gate, unresolved ambiguity,
significant architecture or security judgment, unexpectedly material scope
expansion, destructive or externally consequential authority, a hard external
dependency, or an enforced cycle cap. Work size, difficulty, elapsed time,
context pressure, and inconvenience are not pause classes.

The enforceable success boundary remains the existing server-side readiness or
completion tool. Structural policy can prove that parent and delegated prompts
carry the resume/persistence contract and reject a step envelope that labels a
preparatory action as complete. It cannot prove that a language model never
ends a chat early; do not claim otherwise in tests or documentation.

## Cross-Cutting Concerns to Reuse

- **Repository and Git identity:** `ensureGitRepo`, `getOwnerRepo` with no
  `GH_REPO` fallback for mutations, `isSafeGitRefName`, and the current branch
  shape rules.
- **Process boundary:** `execFile` with fixed argv elements and an explicit
  `cwd`. Do not use shell interpolation for issue numbers, refs, paths, bodies,
  authorization text, or cleanup commands.
- **Configuration:** the strict `.ground-control.yaml` parser and
  `workflow.base_branch`. The principles and per-run checkout authorization
  are not repository configuration knobs.
- **Durable records:** issue-thread pagination, phase-marker parsing/posting,
  trusted-poster checks where a marker grants authority, deterministic
  renderers, and stable `{ok, error, message, next_action}` envelopes.
- **Content safety:** `detectSensitiveBodyContent`,
  `rejectReservedMarkerSequence`, GitHub body-size limits, and bounded
  caller-controlled fields.
- **Deferral enforcement:** `classify_deferral_language`,
  `tools/policy/deferral_cases.json`, and
  `.claude/hooks/block-defer-language.py`. Extend the shared golden cases and
  surface vocabulary instead of adding an unrelated blame/scope classifier.
  Final/readiness posters need server-side enforcement because they post with
  `gh api` and bypass the Claude Bash hook.
- **Routing and state:** `gc_resolve_workflow_route`,
  `DEFAULT_IMPLEMENT_ROUTING_STAGES`, the existing compact step envelope, and
  issue-thread state. Do not create a second router or treat the issue cache,
  subagent cache, or telemetry JSONL as workflow truth.
- **Completion:** `validateFinalReportInput`, `runPostFinalReport`,
  `runAssertCompletion`, phase prerequisites, CI/Sonar checks, and
  traceability reconciliation. Add the obligation-clear assertion to this
  composition instead of creating a competing completion endpoint.
- **Distribution and policy:** `bin/install-skills.sh`, the Cursor wrapper,
  `scripts/bootstrap-claude-workflow.sh`, `tools/policy/checks.py`,
  `architecture/policies/adr-policy.json`, and `make policy`.

## Security and Validation Layers

- **MCP schema:** use positive issue identifiers, closed enums for checkout
  mode, obligation event, category, and disposition, bounded text and arrays,
  and exact branch/ref shapes. Apply Zod at registration and semantic
  validation in the library runner.
- **Repository containment:** require an absolute invocation root, canonicalize
  it, reject symlink or `..` escapes, and compare the post-mutation top-level
  with the pinned root. Any future cleanup target must be generated under a
  contained run directory and validated before deletion.
- **GitHub identity and authorization:** derive the repository from the
  checkout's origin. Do not use `GH_REPO` as a mutation fallback. A future
  worktree grant must be a trusted, durable issue-thread marker, not
  agent-supplied authorization prose.
- **Secrets and OS exposure:** GitHub authentication stays in the host's `gh`
  environment. Never place tokens or environment dumps in process argv,
  telemetry, returned command failures, comments, or paths. Issue-thread bodies
  follow the existing filtered, bounded `gh api` argv-posting convention; they
  must contain no secret-bearing evidence or raw environment content.
- **Marker and report safety:** reject caller-controlled `<!-- gc:` content,
  sensitive text, oversized bodies, invalid state transitions, and
  scope/blame-based non-action language before network I/O.
- **Error surface:** expected branch, authorization, obligation, and
  completion failures return stable structured envelopes without raw command
  output or stack traces. Existing MCP telemetry may count the tool call, but
  obligation bodies and principles text are not telemetry fields.
- **Backend/API boundary:** no backend controller, DTO, service, repository,
  database schema, `ErrorResponse`, or authentication change is needed. Do not
  route this host-side workflow contract through the product REST API.

## Whole-Repository Guardrails

The implementation must keep the canonical skill, every step file, workflow
docs, ADR-021/ADR-029/ADR-031/ADR-036, MCP tool descriptions and tests,
policy checks and golden cases, Claude hook copies, supported-driver wrappers,
and installer behavior consistent.

The existing `workflow-guardrail-sync` trigger lists
`skills/implement/SKILL.md` but not `skills/implement/steps/**`. Close that
hole: a delegated-step edit can currently change operative behavior without
triggering ADR/workflow synchronization. The principles file must also be a
guarded surface. Keep `/quickfix` behavior explicit when synchronization
requires touching its documentation; do not silently import `/implement`'s
new obligation or checkout semantics into that lane.

Correct contradictory text throughout the canonical `/implement` surfaces.
In particular:

- “minimum change” and “no scope creep” limit speculative changes, not repair
  of a real problem encountered during the run;
- “outside the diff's scope” is not a valid reason for
  `not-applicable`;
- a step-specific cap pauses with an open obligation and a decision request;
  and
- provenance may be recorded for diagnosis or audit, but “pre-existing,”
  “unrelated,” “owned elsewhere,” or “outside this PR” cannot justify
  non-action.

Use behavioral tests for the branch boundary and completion refusal. Use
structural policy tests for load-before-route ordering, immutable contract
propagation, parent and delegated prompts, supported-driver wrappers,
contradictory workflow language, and the direct-worktree-command ban. Keep the
shared language golden cases broad enough to distinguish prohibited excuses
from neutral provenance, historical explanation, and legitimate issue
non-goals.

## Non-Goals and Anti-Patterns

- No change to the one-human-touchpoint contract, review caps, Phase E
  ordering, traceability semantics, or the prohibition on agent PR merges.
- No worktree change to `/integrate`, and no incomplete worktree exception in
  `/implement`.
- No new database, backend workflow entity, local durable ledger, Git note,
  branch-keyed state, or Temporal worker.
- No new `.ground-control.yaml` knob for principles, persistence, or per-run
  user authorization.
- No second decision-record schema for review findings, second deferral
  classifier, second GitHub client, or second completion tool.
- No blanket keyword ban on “pre-existing,” “scope,” “ownership,” or
  provenance. Classification is surface-aware and targets their use as a
  reason for unresolved non-action.
- No policy test that snapshots prose or merely checks one substring. Test
  ordering, required contract fields, allowed state transitions, and
  executable refusal behavior.
