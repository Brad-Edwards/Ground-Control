# Ground Control Workflow

How to use Ground Control as a requirements-driven development platform — from idea to shipped, traceable, auditable software.

## Philosophy

Ground Control treats every artifact in the software lifecycle as a node in a graph: requirements, code files, tests, ADRs, operational assets, verification results. Every relationship is an edge. The graph is the single source of truth — no spreadsheets, no disconnected tools, no traceability theater.

The workflow is designed for AI-assisted development. Requirements are authored collaboratively with an AI agent via MCP, implementation is driven by requirement UIDs, and traceability links close the loop automatically. The platform doesn't just track what you built — it tracks *why* you built it, *what* proves it works, and *what breaks* if it changes.

## The Graph

```
Requirement ──PARENT──► Requirement
     │                       │
     ├──DEPENDS_ON──►        ├──IMPLEMENTS──► Code File
     ├──CONFLICTS_WITH──►    ├──TESTS──► Test File
     ├──REFINES──►           ├──DOCUMENTS──► ADR
     ├──SUPERSEDES──►        ├──CONSTRAINS──► Config/Policy
     └──RELATED──►           └──VERIFIES──► Proof/Spec
                                    │
                             Operational Asset
                              ├──CONTAINS──►
                              ├──DEPENDS_ON──►
                              ├──COMMUNICATES_WITH──►
                              └──TRUST_BOUNDARY──►
```

Every node has a lifecycle. Every edge has a type. Every change is versioned. One Cypher query can traverse the entire graph.

## Phase 1: Define Requirements

### Create a Project

Every requirement lives in a project. Projects scope all operations — analysis, baselines, quality gates, documents.

```
gc_create_project(identifier: "my-system", name: "My System")
```

### Author Requirements

Requirements are the atomic unit. Each has a human-readable UID (e.g., `GC-R001`), a title, a formal statement, optional rationale, type (FUNCTIONAL, NON_FUNCTIONAL, CONSTRAINT, INTERFACE), MoSCoW priority (MUST, SHOULD, COULD, WONT), and a wave number for release planning.

```
gc_create_requirement(
  uid: "GC-R001",
  title: "User Authentication",
  statement: "The system shall authenticate users via OAuth2 before granting access to protected resources.",
  requirement_type: "FUNCTIONAL",
  priority: "MUST",
  wave: 1
)
```

Requirements start in DRAFT status. The lifecycle is forward-only:

```
DRAFT → ACTIVE → DEPRECATED → ARCHIVED
```

Transition to ACTIVE when the requirement is reviewed and approved. Every transition is recorded in the audit trail with timestamp, actor, and optional reason.

### Import Existing Specs

Already have requirements? Import them:

- **StrictDoc (.sdoc):** `gc_import_strictdoc` — imports documents, sections, text blocks, requirements, and relations. Idempotent by UID.
- **ReqIF 1.2 (.reqif):** `gc_import_reqif` — standard interchange format compatible with IBM DOORS, Polarion, Jama. Idempotent by IDENTIFIER.

### Build the Requirements Graph

Connect requirements with directed relations to model dependencies, hierarchy, and conflicts:

| Relation | Meaning |
|----------|---------|
| `PARENT` | Hierarchical containment — parent covers child |
| `DEPENDS_ON` | Blocking dependency — child can't be done until parent is done |
| `CONFLICTS_WITH` | Mutual exclusion — both cannot be active simultaneously |
| `REFINES` | Elaboration — child is a concrete version of abstract parent |
| `SUPERSEDES` | Replacement — new requirement replaces old |
| `RELATED` | Weak association for reference |

```
gc_create_relation(
  source_id: <req-uuid>,
  target_id: <other-req-uuid>,
  relation_type: "DEPENDS_ON"
)
```

### Validate the Graph

Before committing to implementation, run analysis to catch structural problems:

- **`gc_analyze_cycles`** — Circular dependencies that block delivery
- **`gc_analyze_orphans`** — Requirements disconnected from the graph
- **`gc_analyze_cross_wave`** — Wave 2 requirements depending on Wave 3 (ordering violation)
- **`gc_analyze_consistency`** — Active requirements linked by CONFLICTS_WITH
- **`gc_analyze_completeness`** — Missing fields, status distribution
- **`gc_run_sweep`** — All of the above in one call

Or run `gc_dashboard_stats` for an aggregate health view: counts by status and wave, coverage percentages, and recent change activity.

### Find Duplicates

If you have a large requirement set, use semantic similarity to find near-duplicates:

```
gc_embed_project(project: "my-system")   # Generate embeddings
gc_analyze_similarity(threshold: 0.85)    # Find similar pairs
```

Merge or refactor duplicates before they become parallel implementations.

## Phase 2: Plan & Prioritize

### Wave-Based Planning

Requirements are grouped into waves (release increments). Wave 1 ships first, Wave 2 second, etc. MoSCoW priority ranks within a wave.

`gc_get_work_order` returns a topologically sorted queue: requirements ordered by wave, then by MoSCoW priority, with blocked requirements (unresolved dependencies) flagged.

### Organize into Documents

For stakeholder communication, organize requirements into narrative specifications:

```
Document: "System Requirements Specification v1.0"
  ├── Section: "1. Introduction"
  │     └── Text: "This document defines..."
  ├── Section: "2. Authentication"
  │     ├── Requirement: GC-R001 (User Authentication)
  │     ├── Requirement: GC-R002 (Session Management)
  │     └── Text: "OAuth2 was selected because..."
  └── Section: "3. Data Storage"
        └── Requirement: GC-R003 (Encryption at Rest)
```

Documents support arbitrary nesting, mixed content (requirement references + prose), optional grammars (custom fields, allowed types), and export to StrictDoc, HTML, PDF, or ReqIF.

### Set Quality Gates

Define pass/fail thresholds that requirements must meet before code ships:

```
gc_create_quality_gate(
  name: "Test Coverage",
  metric_type: "COVERAGE",
  metric_param: "TESTS",
  operator: "GTE",
  threshold: 80,
  scope_status: "ACTIVE"
)
```

Available metrics: `COVERAGE` (% of requirements with a link type), `ORPHAN_COUNT`, `COMPLETENESS`. Gates evaluate in CI via `gc_evaluate_quality_gates`.

For this repository, the human-maintained policy entrypoints are:

- `make policy` for repo-native guardrails shared by Claude and Codex
- `make sync-ground-control-policy` to sync ADR metadata and quality-gate definitions into Ground Control
- `make policy-live` to validate live Ground Control gates and non-regression sweep baselines when a reachable GC instance is available

## Phase 3: Implement

### The Development Loop

Pick the next unblocked requirement from the work order and implement it. Ground Control's `/implement` skill automates the entire cycle:

- The current repository's Ground Control context (project id, workflow commands, SonarCloud settings, plan rules) lives in `.ground-control.yaml` at the repo root, with larger rule files under `.gc/`. `AGENTS.md` carries a brief pointer to this config so agents know where to look.
- The `/implement` skill validates this up front via `gc_get_repo_ground_control_context` — a single call that returns the full workflow config — and stops rather than guessing if the repo context is missing or invalid.
- The `/implement` argument should be the full requirement UID as it already exists in Ground Control.

1. **Fetch requirement** from Ground Control
2. **Create GitHub issue** and link it via traceability
3. **Checkout feature branch** via `gh issue develop --name <issue-number>-<short-slug>` — the `--name` argument is mandatory (skipping it lets `gh` auto-derive a slug from the full issue title, which produces 100+ character branch names that break terminal display, copy-paste, CI breadcrumbs, and downstream shell quoting). Total branch name ≤ 50 characters, ASCII-only. **Then validate the actual checked-out branch against the same rule** — `gh` reuses an existing branch if the issue was already picked up, so a previous pickup that ran before this rule existed will hand the agent a non-compliant branch. The post-check compares against the *remote* base (`origin/<base>` after a fetch — local base can be stale) and renames in place only when no commits or PR exist; otherwise the agent applies the in-progress signal first (so a paused picked-up issue is still visibly flagged) then stops and escalates to the user. The post-check is the dispositive enforcement (the `--name` flag only governs first-time pickups). See `skills/implement/SKILL.md` Step 1 sub-step 11 for the slug-derivation rule, the validation predicate, and worked examples. Then **flag the issue in-progress** — apply an `in-progress` label (created on demand if the repo lacks it) and post a pickup comment on the thread (driver, branch, timestamp) so a maintainer scanning the issue list, or another agent, can see at a glance that the issue is in flight. The label is removed when the workflow closes the issue (and intentionally stays put if a run escalates without finishing).
4. **Plan implementation** — posted as a comment on the GitHub issue thread per ADR-029. The workflow proceeds directly to TDD without a synchronous user-approval gate; the user owns review at PR merge.
5. **Write code, tests, docs** — clause-by-clause verification against the requirement statement. TDD is mandatory except for the narrow documentation-only carve-out documented in `skills/implement/SKILL.md` Step 4.4 (no executable behavior in the diff + every clause/criterion protected by a named structural gate; declared in the plan and re-stated on the issue thread). The completion gate re-validates the carve-out with a two-check sweep over the union of committed and uncommitted paths — both the path set and the diff hunk content must be doc-only — because a path-only check can miss executable behavior buried in a doc file, and an HEAD-only check would miss uncommitted changes still in the working tree.
6. **Transition to ACTIVE** once implemented and verified — the API enforces `IMPLEMENTS-only-on-ACTIVE`, so transition MUST happen before the link-creation step.
7. **Create traceability links** (after the transition above):
   - `IMPLEMENTS` → code files that satisfy the requirement. When the diff finalizes/documents a requirement whose structural implementation lives in pre-existing files (shipped under a sibling requirement), `IMPLEMENTS` links are backfilled onto those pre-existing artifacts of record, bounded by the requirement's concrete subject matter.
   - `TESTS` → test files that verify the requirement
   - `DOCUMENTS` → ADRs or design docs that explain the approach (also used for forward-looking requirements that the diff references but does not yet ship)

Before you stop, run `make policy` alongside the feature-specific verification commands. This catches ADR drift, missing controller/MCP/doc parity, migration companion updates, and PR body omissions before review.

### Record Architectural Decisions

When implementation involves a design choice, create an ADR:

```
gc_create_adr(
  uid: "ADR-018",
  title: "AWS EC2 Deployment",
  decision_date: "2026-03-15",
  context: "Need a deployment target for the application",
  decision: "Deploy to AWS EC2 with Docker",
  consequences: "Simple, cost-effective, single-instance"
)
```

ADRs have their own lifecycle (PROPOSED → ACCEPTED → DEPRECATED | SUPERSEDED) and link to the requirements they impact via traceability. `gc_get_adr_requirements` shows reverse traceability: given an ADR, which requirements does it affect?

### Model Operational Assets

For systems with infrastructure concerns, model the operational landscape:

```
gc_create_asset(
  uid: "ASSET-001",
  name: "API Gateway",
  asset_type: "SERVICE",
  description: "NGINX reverse proxy"
)
```

Asset types: APPLICATION, SERVICE, DATABASE, NETWORK, HOST, CONTAINER, IDENTITY, DATA_STORE, BOUNDARY. Assets connect via relations: CONTAINS, DEPENDS_ON, COMMUNICATES_WITH, TRUST_BOUNDARY, SUPPORTS, ACCESSES, DATA_FLOW.

Asset topology supports cycle detection and impact analysis — "what services are affected if this database goes down?"

## Phase 4: Verify & Ship

### Quality Gate Evaluation

Before merging, evaluate all quality gates:

```
gc_evaluate_quality_gates(project: "my-system")
```

Returns overall pass/fail + per-gate details (actual value vs. threshold). Fix failures: write missing tests, link orphaned requirements, clean up duplicates.

### Architecture + Review Pipeline

Per issue #804, the `/implement` skill runs one mandatory Codex architecture preflight before coding and then a small set of independent verification/review stages before the PR is presented for human review:

1. **Codex architecture preflight** (Step 2.5) — cross-cutting concerns, reuse opportunities, abstraction/concept confusion, ADR/design guidance when needed.
2. **Pre-push Codex review** (Step 6.5, default cap 1; configurable via `workflow.codex_review.pre_push_cap` in `.ground-control.yaml`). Production-readiness review (`gc_codex_review`, core + security reviewers) runs against the staged + unstaged diff *before* the first push, so each fix iteration is local (~5 min) instead of a CI/SonarCloud roundtrip (10–15 min). Every successful cycle posts a verbatim findings record to the resolved issue thread (durable per ADR-029) plus inline PR review comments when a PR exists. The cap default dropped from 3 to 1 in issue #906 — cycles 2 and 3 empirically compound the agent's own fix-introduced bugs more than they catch net-new bugs, and CI / SonarCloud / the human reviewer cover the residual risk; repos that want the historical cap-3 behavior set the knob explicitly.
3. **Pre-push test-quality review** (Step 6.6, default cap 1; configurable via `workflow.test_quality_review.pre_push_cap`). Moved pre-push by issue #906 from the former post-PR Step 13. `gc_test_quality_review` (MCP tool) catches assertion-free tests, mock-only assertions, integration-as-unit tests, and tests that can't detect regressions. The tool shells out to `claude --print --model claude-sonnet-4-6` with the canonical rubric, parses structured JSON findings, posts the durable record + cycle marker to the issue thread, and returns a `{findings, cycle, cap, next_action, ...}` envelope. The parent /implement workflow reads `next_action` as a directive. Per #884 v2 this replaces the prior `Skill("review-tests")` boundary, which produced prose findings the parent kept echoing back to the user instead of fixing in-turn. Cycle cap is server-side and lane-agnostic. Authentication: the wrapper strips `ANTHROPIC_API_KEY` so claude uses the host's OAuth session. Full mechanism in `architecture/notes/test-quality-review-engine.md`.
4. **CI + SonarCloud** (Steps 10 / 11) — static analysis, coverage, duplication, security hotspots run after push. With both AI-assisted reviewers clean pre-push, the PR opens for human review only after every mechanical and AI-assisted check has passed; there is no post-push review fix loop (former Step 13 / Step 14 were merged out by #906).

The post-push codex review (former Step 12) was removed by issue #804: the pre-push pass catches everything codex would normally flag, and merge-commit drift is the responsibility of CI (compile/tests/integration) and SonarCloud (quality), not a duplicate codex run. The post-push tool entrypoint (`gc_codex_review` with a `pr_number`) remains as defense-in-depth for direct callers but the SKILL no longer drives it.

**PR title format (issue #901).** Step 9 validates the title locally before `gh pr create`: a single conventional-commit type with optional scope (`<type>(<optional-scope>): <subject>`, no compound `security/docs:` prefixes), and a lowercase-leading subject (`^[a-z].*$` — uppercase acronyms like NGFW/MCP must be reshaped). Per-repo override via `.ground-control.yaml::workflow.pr_title.types` / `subject_pattern`; otherwise the conventional-commits canonical allow-list applies. Catching both locally removes the edit-cycle-per-failure cost the agent otherwise pays after every `gh pr create` rejection by `amannn/action-semantic-pull-request` or an equivalent downstream linter. Full rule + reshape examples in `skills/implement/SKILL.md` Step 9.

All findings are fixed before the PR is presented for human review. "Defer" is not a valid disposition (ADR-029) and is mechanically enforced (issue #830): the `.claude/hooks/block-defer-language.py` PreToolUse hook blocks GitHub issue/PR text carrying deferral-disposition language, and `bin/policy` flags it in the PR body at completion gate. The only valid dispositions are `fix`, `wontfix` (with explicit user authorization), or `not-applicable` (with rationale); filing a tracking issue does not make a deferral valid. Codex review classifies each finding `one-off` or `class`; a `class` finding is fixed at the category level (one structural point of repair applied to every instance), not site-by-site.

### Impact Analysis

Before shipping, understand the blast radius:

```
gc_analyze_impact(uid: "GC-R001")
```

Returns all transitively affected requirements — everything upstream and downstream that could be impacted by a change to this requirement.

## Phase 5: Release & Audit

### Release Notes via Changelog Fragments

Per-PR release notes ship as fragments under `changelog.d/<issue>.<type>.md` (or `+<slug>.<type>.md` for issue-free entries), where `<type>` is one of `security`, `added`, `changed`, `deprecated`, `removed`, `fixed`. The convention exists so concurrent PRs never conflict on the same `CHANGELOG.md` line range, and it is enforced by `tools/policy/checks.py::run_changelog_fragment_check` plus the `verify-implementation.sh` Stop hook. Source-changing diffs MUST file a fragment (refactors under application source included); CI-only and docs-only diffs may ship without one. Direct `CHANGELOG.md` edits are reserved for release-collation commits. At release time the maintainer runs `uvx towncrier build --version <X.Y.Z> --date <YYYY-MM-DD> --yes`; towncrier collates the fragments into `CHANGELOG.md` immediately after the `<!-- towncrier release notes start -->` marker and removes the fragments it consumed. See [`changelog.d/README.md`](../changelog.d/README.md) for the full convention.

### Create a Baseline

Freeze the requirement set at a release milestone:

```
gc_create_baseline(name: "v1.0", project: "my-system")
```

Baselines capture the Envers revision at creation time. `gc_get_baseline_snapshot` reconstructs the full requirement set as it existed at that point — without maintaining separate copies.

### Compare Releases

```
gc_compare_baselines(baseline_id: <v1>, other_baseline_id: <v2>)
```

Shows added, removed, and modified requirements between two baselines. Essential for release notes, change impact assessment, and regulatory audits.

### Audit Trail

Every change to every entity is versioned:

- `gc_get_requirement_history` — all revisions with timestamps and actors
- `gc_get_requirement_diff` — structured diff between two revisions (per-field changes)
- `gc_get_timeline` — unified chronological stream of all changes to a requirement
- `gc_get_project_timeline` — same, across all requirements in a project
- `gc_export_audit_timeline` — CSV export for compliance reporting

### Export for Stakeholders

- **Traceability matrix:** `gc_export_requirements` (Excel with traceability sheet)
- **Specification document:** `gc_export_document` (PDF, HTML, StrictDoc, ReqIF)
- **Quality report:** `gc_export_sweep_report` (CSV/Excel/PDF with analysis results)
- **Audit log:** `gc_export_audit_timeline` (CSV)

## Graph Queries

The graph enables queries that cross-cut the entire lifecycle:

| Question | How to answer |
|----------|--------------|
| What breaks if this requirement changes? | `gc_analyze_impact` |
| Which requirements have no tests? | `gc_analyze_coverage_gaps(link_type: "TESTS")` |
| Are there circular dependencies? | `gc_analyze_cycles` |
| What's the full dependency chain from A to Z? | `gc_find_paths(source, target)` |
| Show me everything related to feature X | `gc_extract_subgraph(root_uids: [...])` |
| What changed between v1.0 and v2.0? | `gc_compare_baselines` |
| Who changed this and when? | `gc_get_timeline` |
| Are there near-duplicate requirements? | `gc_analyze_similarity` |
| Which ADRs affect this requirement? | Traceability links with artifact_type=ADR |
| Which requirements does this ADR affect? | `gc_get_adr_requirements` |
| What assets are impacted if this service goes down? | Asset impact analysis |

## /implement cost reduction (ADR-036)

The `/implement` workflow has three opt-in cost-side optimizations that ride on top of the same gate contract (one human touchpoint at PR merge, configurable pre-push Codex cap [default 1 cycle per #906; per-repo override via `workflow.codex_review.pre_push_cap`], zero deferral, ADR-021/ADR-029 phase structure).

- **Per-step model routing.** Each `/implement` step carries a capability tier (`low`, `medium`, `high`) and a stable stage/purpose name. `gc_resolve_workflow_route` reads `.ground-control.yaml` and resolves that stage to provider, agent, canonical model id, tier, and fallback policy. Claude Code routes subagent stages to canonical ids such as `claude-haiku-4-5` and `claude-sonnet-4-6`; parent-only high-tier stages use `claude-opus-4-7`. Codex drivers ignore delegation today unless they explicitly call the resolver and external runner. Opt-in per repo via `.ground-control.yaml`'s `routing.enabled` knob (default `false`) plus optional `routing.stages.<stage>` overrides.
- **Durable-record MCP tools.** Three new deterministic tools replace agent-authored long-form comments. `gc_post_decision_record` renders the canonical Step 6.5 decision-record from structured findings; `gc_post_final_report` renders Step 19's final summary from structured input; `gc_render_pr_body` composes a PR body that satisfies `check_pr_body`'s policy gates from structured input (`change_class ∈ {doc-only, source, source+migration}` shapes the integration-tests / changelog-fragment cells). All three filter sensitive content, post under a structured marker family (`gc:decision-record`, `gc:final-report`), and reject `decision: "defer"` server-side.
- **Per-step telemetry.** `gc_log_step_telemetry` appends one JSONL record per routed step to `.gc/telemetry/<issue>-<sanitized-branch>.jsonl` (gitignored, repo-relative, containment-validated). Operational measurement only — never workflow state, never a cycle counter, never compliance evidence. The summarizer (`make implement-cost-summary`) reports wall time and token counts (when the harness surfaces them) per step and per model; dollar-cost translation is explicit future work. Opt-in via `.ground-control.yaml`'s `telemetry.enabled` knob (`gc_log_step_telemetry` itself refuses with a structured `telemetry_disabled` envelope when the knob is off, so callers cannot bypass the opt-in).

All four tools are Temporal-shaped: deterministic, structured-input-in, structured-output-out, no LLM call. GC-O009 inherits them as activities when the Temporal workflow lands.

## MCP Integration

Ground Control exposes its full API as MCP tools. This means an AI agent (Claude Code, Cursor, etc.) can:

- Query requirements and their graph relationships
- Create and manage traceability links as it writes code
- Run analysis to validate the requirement graph
- Create GitHub issues and sync traceability
- Transition requirement statuses as part of the implementation workflow
- Create ADRs when making architectural decisions
- Evaluate quality gates before shipping

The agent doesn't need to leave the editor. Requirements, code, tests, and traceability all live in the same workflow.

## Amendments

**2026-05-19 (issue #931).** Pre-push reviewers (`gc_codex_review`,
`gc_test_quality_review`) return a verdict envelope — `verdict` +
`architectural_read` + `blocking` + capped `notes` — instead of a
`findings[]`-only payload. The decision-record renderer
(`gc_post_decision_record`) accepts and renders the new shape. `.ground-
control.yaml` gains an optional `architecture.vocabulary` block that the
preflight + reviewers consume when present. Test tooling additions: Pitest
mutation testing (`make test-quality`), CI gate on new CRITICAL OSV-scanner
CVEs, ADR-051 (PROPOSED) drafting a SonarCloud gate recalibration. See
`skills/implement/SKILL.md` Step 2.5 / 6.5 / 6.6 and the binding preflight
note at `architecture/notes/ai-review-recalibration-preflight.md`.

**2026-05-21 (issue #937).** The codex review, codex architecture preflight,
and test-quality review MCP tools gain an opt-in `async` mode. Each spawns a
child process that legitimately runs for minutes; run synchronously a single
MCP tool-call blocked past the MCP client's per-call timeout, orphaning the
child with no result handle (issue #893). With `async: true` the tool returns
a `job_id` immediately and the workflow polls the new `gc_codex_job` tool for
the result envelope or cancels a stuck job. `.claude/settings.json` also sets
`MCP_TOOL_TIMEOUT` / `MCP_TIMEOUT` so long-running MCP tools have headroom.
The gate contract, cycle caps, and durable-record posting are unchanged. See
ADR-036 (amendments) for the job model and `skills/implement/steps/step-02.5
/ 06.5 / 06.6` for the operative loop prose.
