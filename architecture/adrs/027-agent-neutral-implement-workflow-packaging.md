# ADR-027: Agent-Neutral Implement Workflow Packaging

## Status

Accepted

## Date

2026-05-03

## Context

ADR-021 defines the gated agentic development loop required by GC-O007. The
current executable workflow is still expressed mainly as Claude Code skill
content and repo-local workflow notes. Several repositories carry similar
copies of that content, which creates drift in gate ordering, reviewer caps,
traceability handling, and driver-specific instructions.

Issue #791 proposes central packaging so the same `/implement` workflow can be
driven by Claude Code and Codex while remaining parameterized by each
repository's `.ground-control.yaml`. The packaging work must not weaken
GC-O007's gate contract or create a second workflow schema.

## Decision

Package the implement workflow as an agent-neutral workflow contract whose
repo-specific values come from `gc_get_repo_ground_control_context`.

### Canonical Source

There must be one versioned canonical workflow source for the implement loop.
Host-local files such as `~/.claude/skills/implement/SKILL.md` may be install
targets, but they are not the architectural source of truth because they cannot
be reviewed, tested, or linked in Ground Control traceability from this repo.

Repo-local skill files are overrides only. An override must be minimal and must
state which repository-specific behavior cannot be represented through
`.ground-control.yaml`.

### Configuration Boundary

`.ground-control.yaml` remains the repository configuration boundary. New
workflow-packaging fields such as docs paths, example paths, UID examples,
plan-approval behavior, preflight behavior, and cross-cutting concern guidance
must extend the existing `gc_get_repo_ground_control_context` schema instead of
being parsed ad hoc by skill prose, shell snippets, or separate runtime code.

The current MCP parser already provides the pattern to reuse:

- strict unknown-key rejection
- explicit defaults for missing optional sections
- repo-relative path handling for config-supplied paths
- structured validation errors returned by the MCP tool instead of driver-local
  guessing
- tests in `mcp/ground-control/lib.test.js` for parser output, defaults, and
  invalid input

Any new path-valued field that is read from or written to disk must reject
absolute paths and `..` traversal. If the implementation opens the target or
creates children beneath it, it must also use realpath containment checks so a
repo-local symlink cannot escape the repository root. The existing knowledge
block resolver in `mcp/ground-control/lib.js` is the reference pattern.

### Gate Semantics

Packaging may change where workflow prose lives and how repo-specific values
are rendered. It must not change the GC-O007 gate semantics without a separate
ADR. **ADR-029 is the companion ADR that amends GC-O007's gate model in this
same PR**: synchronous plan approval is removed as a human touchpoint, and the
GitHub issue thread becomes the durable record of plan, review findings, and
decisions on findings. Read ADR-029 alongside this ADR.

After ADR-029, the gate contract is:

- exactly **one** human touchpoint: PR merge
- no agent merges PRs
- no reviewer findings are deferred to follow-up work
- preflight is performed before implementation
- completion gates and traceability gates remain hard gates
- Codex review runs as a single pre-push pass and is **hard-capped at three cycles** (no escape clause); the post-push codex review (former SKILL Step 12) was removed by issue #804 and remains only as tool-layer defense-in-depth for direct callers
- plan, review findings, and decisions on findings are recorded as comments on
  the GitHub issue thread so the durable record survives PR merge/close

Plan publishing is therefore a uniform, non-configurable behavior under the new
gate model: every `/implement` run posts the plan as a `gh issue comment` and
proceeds directly to TDD. The earlier `plan.approval_gate` config knob is
NOT introduced; it is not needed, since approval is no longer a synchronous
gate. Repos that want a synchronous gate must amend ADR-029 first.

If existing workflow docs, skills, or hooks disagree on sequencing or loop caps,
the centralization work must reconcile them before claiming the packaged
workflow implements GC-O007. Sweep targets at the time of authoring:

- `docs/DEVELOPMENT_WORKFLOW.md` documented a two-cycle Codex review cap while
  `.claude/skills/implement/SKILL.md` still contained three-cycle and five-cycle
  caps in places. The canonical `skills/implement/SKILL.md` produced by this PR
  unifies on the hard-2 cap.
- GC-O007/ADR-021's pre-amendment statement placed traceability-link creation
  and status activation in the quality gate before staging, while newer workflow
  prose described transition and reconciliation after reviews. ADR-029 makes
  the post-reviews timing explicit (since traceability and transition belong to
  the same agent-driven, post-review phase, not a synchronous human gate).

### Reviewer-of-Record Boundary

Codex remains the reviewer of record. Regardless of whether Claude Code, Codex,
or a future Temporal worker drives the workflow, review and verification steps
must route through the Ground Control MCP tools:

- `gc_codex_architecture_preflight`
- `gc_codex_review`
- `gc_codex_verify_finding`

The driver agent must not silently replace those calls with its own local review
mode. If Codex is the driver, it still invokes the MCP review tools so review
identity, prompts, GitHub comments, and verification bookkeeping stay stable.

### Privileged Side-Effect Boundary

Codex is the planner and reviewer, not the GitHub writer. Any workflow step that
creates durable GitHub side effects for Codex-authored output must keep the
privileged write in the Ground Control MCP layer, where the host service owns
`gh` / token configuration and project-scope validation. Codex may return
structured review, verification, or preflight payloads, but it must not be
prompted to invoke `gh` from its sandbox to post PR review comments, issue
comments, replies, or review-thread mutations.

Implementations must validate Codex payloads server-side before posting:
schema shape, positive numeric issue / PR / comment identifiers, repository
resolution, repo-relative paths, line anchors, and existing realpath
containment guards all remain MCP responsibilities. Tool responses must surface
both the Codex-produced findings / decisions and the GitHub write results,
including partial failures, so the issue thread remains the durable record
without hiding transport errors from the caller.

### Relation to GC-O009

This packaging is an interim distribution and configuration model. It must keep
the workflow phases, schema fields, and reviewer-of-record invariant compatible
with the future Temporal orchestration work in GC-O009, but it does not
introduce Temporal, durable queues, resumable execution state, or a new workflow
engine.

## Consequences

### Positive

- One workflow contract can serve both Claude Code and Codex drivers.
- Repo-specific variation is declarative and testable through one MCP context
  schema.
- Workflow drift becomes easier to detect because caps, touchpoints, and
  reviewer routing are tied back to ADR-021 and this ADR.
- The future GC-O009 Temporal workflow can consume the same configuration shape
  rather than reverse-engineering agent-specific prose.

### Negative

- Central packaging adds migration work for repositories that currently carry
  full local skill copies.
- The canonical workflow source and host-local install target must be kept in
  sync by tooling; editing only the installed copy is not acceptable.

### Risks

- Adding a second parser for `.ground-control.yaml` would create schema drift
  and inconsistent validation across drivers.
- Moving workflow text to a user home directory without a versioned source would
  break traceability and reviewability.
- Driver-specific review substitutions would make review outcomes depend on
  whichever agent launched the workflow, undermining the reviewer-of-record
  invariant.
- After ADR-029 removes the synchronous plan-approval gate, agent silence on
  review-finding decisions becomes the new accountability risk. ADR-029
  mitigates by mandating that every finding decision (fix / wontfix /
  not-applicable) is recorded as a comment on the issue thread. `defer` is not
  a valid decision under GC-O007.

## Non-Goals

- Implementing GC-O009's Temporal orchestration.
- Adding a new workflow DSL, policy engine, or plugin substrate.
- Replacing Ground Control traceability services or quality gates.
- Reworking the REST API, Java domain model, persistence schema, or security
  model for workflow packaging alone.

## Related Requirements

- GC-O007 Gated Agentic Development Loop
- GC-O009 Workflow Orchestration via Temporal

## Related ADRs

- ADR-021 Gated Agentic Development Loop (amended by ADR-029)
- ADR-023 Plugin Architecture
- ADR-029 Issue-Thread Gate Model (companion ADR landing in the same PR)

## Amendments

**2026-05-26 (issue #989).** The `workflow.integration_manager` block is now a recognized member of the `workflow.*` schema. Keys: `approval_label` (string, default `approved-for-integration`), `ordering` (enum `pr_number_asc` / `pr_number_desc` / `approved_at_asc`, default `pr_number_asc`), `max_queue_size` (int [1, 100], default 20). The parser (`normalizeIntegrationManagerConfig` in `mcp/ground-control/lib.js`) enforces the same strict-unknown-key rule as the rest of the workflow config. See GC-O011.

**2026-05-19 (issue #931): `architecture.vocabulary` schema extension.** The
`.ground-control.yaml` schema gains an optional top-level `architecture` block
with a `vocabulary` sub-block: `patterns[]`, `canonical_helpers[]`,
`boundary_contract`, `binding_adrs[]`, `anti_recommendations[]`. Optional;
strict unknown-key rejection; `example_path` and `path` values are repo-
relative and validated via `resolveRepoRelativePath` + `assertRealpathInRepo`.
`gc_get_repo_ground_control_context` returns the block as
`cfg.architecture.vocabulary`. The block is per-repo content; the workflow
ships the consumption machinery (Codex preflight + pre-push reviewers) and
falls back to workflow-level defaults when absent. See issue #931 and the
preflight note at `architecture/notes/ai-review-recalibration-preflight.md`.
