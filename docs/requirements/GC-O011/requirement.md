---
id: GC-O011
title: "Approved Pull Request Integration Manager Workflow"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-05-25T21:07:30.409377Z
updated_at: 2026-05-26T00:57:44.569706Z
---

# GC-O011 — Approved Pull Request Integration Manager Workflow

## Statement

The system shall expose an integration-manager workflow that prepares maintainer-approved pull requests in a target repository for merge by deterministically discovering, ordering, rebasing, and verifying them against the configured base branch, while preserving the GC-O007 / ADR-029 contract that PR merge remains the single human touchpoint.

(a) Discovery and ordering. Accept a target repository and discover only those open pull requests carrying an explicit maintainer approval signal (configurable label name, default `approved-for-integration`). Produce an ordered integration plan before modifying any branch, including ordering, base branch, and a per-PR readiness assessment.

(b) Concurrency control. Acquire a repo-level integration lock for the duration of the run so two concurrent integration managers cannot race on the same repository. Release on normal completion, on explicit abort, and via a bounded expiry that prevents stale locks from permanently blocking the lane.

(c) Branch preparation. For each PR in planned order, update or rebase the PR branch onto the current base in an isolated worktree, attempt to resolve mechanical conflicts where possible, run the repository's configured completion gate (`cfg.workflow.completion_command`), and watch the existing CI / SonarCloud signals (Step 10 / Step 11 of GC-O007) before marking the PR ready.

(d) Prepare-only default. Default mode is prepare-only: the workflow shall not enqueue or merge. Enqueue and merge modes shall be opt-in flags that require a separate ADR amendment to GC-O007 / ADR-029 to be in effect before they may be enabled; until that ADR change lands, the workflow shall refuse to execute those modes regardless of flag.

(e) Failure semantics. When a PR cannot be rebased mechanically, has unresolvable conflicts, or fails the completion gate / CI / Sonar in a way that does not meet clause (h)'s consultation criteria, record the blocked PR with actionable detail and continue with the remaining queue entries unless the failure indicates a queue-wide concern (for example, the base branch is broken). Blocked PRs remain visible in the workflow output. When a failure meets clause (h)'s criteria, the workflow shall stop the entire run per (h) rather than continue the queue.

(f) Maintainer approval signal. The label name (and any required state) shall be configurable per repository through `.ground-control.yaml`. The default value shall be a single repository label whose presence on an open PR is sufficient and necessary for queue entry.

(g) Documentation and policy. The workflow shall be documented in the development workflow reference, including maintainer responsibilities, the approval signal convention, queue semantics, allowed modes, and the explicit list of operations that remain off-limits to agents (notably merge). Any future enablement of enqueue or merge modes shall be reflected in updated ADR text and updated workflow documentation before the implementation toggles those modes on.

(h) Maintainer consultation criteria — stop the run and ask. The workflow shall proceed autonomously by default. When it encounters any of the following conditions, it shall stop the entire integration run (not just the affected PR), preserve in-progress state, and consult the maintainer directly through the invoking interface (Claude Code or Codex session, or equivalent interactive channel): (i) a genuine ambiguity it cannot resolve from the PR, the requirement, the repository configuration, or the documentation; (ii) a genuine conflict between authoritative inputs (for example, the PR conflicts with a requirement statement; two requirements conflict on the same surface) where it is not clear which side is correct; or (iii) what appears to be an error or material oversight in the PR, the requirement, the policy, or the documentation. Routine decisions — mechanical conflict resolution, choice of rebase ordering, retrying a transient failure within a bounded retry budget, or any other case that does not meet (i)–(iii) — shall not stop the run.

(i) Fixes preserve tests, documentation, and standards. When the workflow modifies a PR's branch (rebase, conflict resolution, or any other change beyond pure verification), the modification shall keep the PR's tests, documentation, and repository coding standards in working order. The workflow shall not land a fix that suppresses a failing test rather than addressing it, removes or stubs documentation rather than updating it, bypasses the repo's format / lint commands, or otherwise weakens the repo's quality gates as a shortcut. A fix that would require silencing or weakening an existing test, doc, or standard shall fall under clause (h) — the workflow shall stop the run and consult the maintainer rather than silently relax the standard.

## Rationale

Maintainers serializing multiple agent-authored PRs after the base branch moves spend disproportionate time on mechanical rebase / verify cycles that an agent can perform deterministically. GC-O007 already constrains the agentic development lifecycle for individual PRs; this requirement extends that contract to a fleet-of-PRs lane while preserving the workflow's single human touchpoint at merge. ADR-029 makes the issue thread the durable record; the integration-manager workflow inherits that record convention. The prepare-only default and the explicit policy/ADR gate for enqueue/merge modes ensure that automation cannot quietly remove the human merge touchpoint without an explicit governance change. The label-based discovery model is mechanical and auditable. The repo-level lock prevents two integration managers from racing on the same repository — a real failure mode once the lane is parallelizable. Clauses (h) and (i) reflect the project standing rule "Fix root causes. Do not jury-rig." applied to the integration lane: the workflow handles routine mechanical work autonomously but stops the run and asks the maintainer the moment it encounters a genuine ambiguity, an authoritative-input conflict, or a fix that would require silencing tests, removing documentation, or bypassing the repo's quality gates. Stopping the whole run rather than continuing the queue on a (h)-class problem keeps the maintainer's review surface scoped to one decision at a time and prevents downstream queue entries from inheriting an unresolved scope question.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-integrate.js` (Integration manager MCP tool (gc_integration_manager) — implements all GC-O011 clauses)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib: workflow.integration_manager parser + acquireIntegrationLock)
- IMPLEMENTS → DOCUMENTATION `skills/integrate/SKILL.md` (Integration manager skill — prose contract for the lane)
- IMPLEMENTS → GITHUB_ISSUE `989` (Issue #989: Approved Pull Request Integration Manager Workflow)
- IMPLEMENTS → PULL_REQUEST `1033` (PR #1033: Approved Pull Request Integration Manager Workflow)
- CONSTRAINS → ADR `architecture/adrs/027-agent-neutral-implement-workflow-packaging.md` (ADR-027: Agent-Neutral Implement Workflow Packaging — constrains integration-manager single-touchpoint contract and config surface)
- CONSTRAINS → ADR `architecture/adrs/029-issue-thread-gate-model.md` (ADR-029: Issue-Thread Gate Model — constrains integration-manager prepare-only default and merge gate)
- TESTS → TEST `mcp/ground-control/lib.parsegroundcontrolyaml-workflow-integration-mana.test.js` (MCP lib tests: integration_manager config parser)
- TESTS → TEST `mcp/ground-control/gc-integrate.gc-integration-manager-mode-merge.test.js` (Integration manager MCP tool tests)
