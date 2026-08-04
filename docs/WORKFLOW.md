# Ground Control Workflow

How to use Ground Control as a requirements-driven development platform: from idea to shipped, traceable, auditable software.

> **Re-platformed (issue #1500).** Ground Control is now the MCP server for the `/implement` workflow over repo-local files, not a graph platform. Requirements live as `docs/requirements/<UID>/requirement.md` files (ADR-093) and ADRs as `architecture/adrs/*.md`; there is no backend, database, or graph. The graph framing below is historical; the repo files are the record.

## Philosophy

Ground Control treats every artifact in the software lifecycle as a node in a graph: requirements, code files, tests, ADRs, operational assets, verification results. Every relationship is an edge. The graph is the single source of truth (no spreadsheets, no disconnected tools, no traceability theater).

The workflow is designed for AI-assisted development. Requirements are authored collaboratively with an AI agent via MCP, implementation is driven by requirement UIDs, and traceability links close the loop automatically. The platform doesn't just track what you built; it tracks *why* you built it, *what* proves it works, and *what breaks* if it changes.

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

Every requirement lives in a project. Projects scope all operations: analysis, baselines, quality gates, and documents.

```
gc_create_project(identifier: "my-system", name: "My System")
```

### Author Requirements

Requirements are the atomic unit. Each has a human-readable UID (for example, `GC-R001`), a title, a formal statement, optional rationale, type (FUNCTIONAL, NON_FUNCTIONAL, CONSTRAINT, INTERFACE), MoSCoW priority (MUST, SHOULD, COULD, WONT), and a wave number for release planning.

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
  └───────────────►
```

Transition to ACTIVE when the requirement is reviewed and approved. `DRAFT → DEPRECATED` withdraws a requirement that was never implemented, so retiring unbuilt work does not require promoting it through ACTIVE first and claiming it shipped. Every transition is recorded in the audit trail with timestamp, actor, and optional reason.

### Import Existing Specs

Already have requirements? Import them:

- **StrictDoc (.sdoc):** `gc_import_strictdoc` (imports documents, sections, text blocks, requirements, and relations). Idempotent by UID.
- **ReqIF 1.2 (.reqif):** `gc_import_reqif` (standard interchange format compatible with IBM DOORS, Polarion, Jama). Idempotent by IDENTIFIER.

### Build the Requirements Graph

Connect requirements with directed relations to model dependencies, hierarchy, and conflicts:

| Relation | Meaning |
|----------|---------|
| `PARENT` | Hierarchical containment; parent covers child |
| `DEPENDS_ON` | Blocking dependency; child can't be done until parent is done |
| `CONFLICTS_WITH` | Mutual exclusion; both cannot be active simultaneously |
| `REFINES` | Elaboration; child is a concrete version of abstract parent |
| `SUPERSEDES` | Replacement; new requirement replaces old |
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

- **`gc_analyze_cycles`**: circular dependencies that block delivery
- **`gc_analyze_orphans`**: requirements disconnected from the graph
- **`gc_analyze_cross_wave`**: Wave 2 requirements depending on Wave 3 (ordering violation)
- **`gc_analyze_consistency`**: active requirements linked by CONFLICTS_WITH
- **`gc_analyze_completeness`**: missing fields, status distribution
- **`gc_run_sweep`**: all of the above in one call

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

Available (and enforced) metric types: `COVERAGE` (% of requirements with a given link type: `IMPLEMENTS`, `TESTS`, or `DOCUMENTS`), `ORPHAN_COUNT`, and `COMPLETENESS`. Gates evaluate in CI via `gc_evaluate_quality_gates`, and the `/implement` completion gate (Step 6) blocks a run on any failing gate via `gc_assert_quality_gates`; its failure envelope lists each failing gate as `{name, metric_type, threshold, actual}`.

For this repository, the human-maintained policy entrypoints are:

- `make policy` for repo-native guardrails shared by Claude and Codex
- `make sync-ground-control-policy` to sync ADR metadata and quality-gate definitions into Ground Control
- `make policy-live` to validate live Ground Control gates and non-regression sweep baselines when a reachable GC instance is available

`make policy` is this repository's `workflow.policy_command` in
`.ground-control.yaml`. The `/implement` and `/quickfix` gates run whatever that
field names (it defaults to `make policy`), so a consuming repository whose
policy gate has a different name configures it there rather than being expected
to own a `policy` Make target. The gate is never skipped when the command is
missing - it fails.

## Phase 3: Implement

### The Development Loop

Pick the next unblocked requirement from the work order and implement it. Ground Control's `/implement` skill automates the entire cycle:

- The current repository's Ground Control context (project id, workflow commands, SonarCloud settings, plan rules) lives in `.ground-control.yaml` at the repo root, with larger rule files under `.gc/`. `AGENTS.md` carries a brief pointer to this config so agents know where to look.
- The `/implement` skill validates this up front via `gc_get_repo_ground_control_context` (a single call that returns the full workflow config) and stops rather than guessing if the repo context is missing or invalid.
- The `/implement` argument should be the full requirement UID as it already exists in Ground Control.

1. **Fetch requirement** from Ground Control
2. **Create GitHub issue** and link it via traceability
3. **Checkout feature branch** via `gh issue develop --name <issue-number>-<short-slug>`. The `--name` argument is mandatory (skipping it lets `gh` auto-derive a slug from the full issue title, which produces 100+ character branch names that break terminal display, copy-paste, CI breadcrumbs, and downstream shell quoting). Total branch name ≤ 50 characters, ASCII-only. **Then validate the actual checked-out branch against the same rule**. `gh` reuses an existing branch if the issue was already picked up, so a previous pickup that ran before this rule existed hands the agent a non-compliant branch. The post-check compares against the *remote* base (`origin/<base>` after a fetch; local base can be stale) and renames in place only when no commits or PR exist; otherwise the agent applies the in-progress signal first (so a paused picked-up issue is still visibly flagged) then stops and escalates to the user. The post-check is the dispositive enforcement (the `--name` flag only governs first-time pickups). See `skills/implement/SKILL.md` Step 1 sub-step 11 for the slug-derivation rule, the validation predicate, and worked examples. Then **flag the issue in-progress**: apply an `in-progress` label (created on demand if the repo lacks it) and post a pickup comment on the thread (driver, branch, timestamp) so a maintainer scanning the issue list, or another agent, can see at a glance that the issue is in flight. The in-progress label removal is optional best-effort after Step 17 completion (#1103); it is no longer a mandatory step gate. The GitHub issue itself closes via `Closes #<issue-number>` in the PR body when the user merges. (The label intentionally stays put if a run escalates without finishing.)
4. **Plan implementation**: posted as a comment on the GitHub issue thread per ADR-029. The workflow proceeds directly to TDD without a synchronous user-approval gate; the user owns review at PR merge.
5. **Write code, tests, docs**: clause-by-clause verification against the requirement statement. TDD is mandatory except for the narrow documentation-only carve-out documented in `skills/implement/SKILL.md` Step 4.4 (no executable behavior in the diff + every clause/criterion protected by a named structural gate; declared in the plan and re-stated on the issue thread). The completion gate re-validates the carve-out with a two-check sweep over the union of committed and uncommitted paths (both the path set and the diff hunk content must be doc-only) because a path-only check can miss executable behavior buried in a doc file, and an HEAD-only check would miss uncommitted changes still in the working tree.
6. **Transition to ACTIVE** once implemented and verified; the API enforces `IMPLEMENTS-only-on-ACTIVE`, so transition MUST happen before the link-creation step.
7. **Create traceability links** (after the transition above):
   - `IMPLEMENTS` → code files that satisfy the requirement. When the diff finalizes/documents a requirement whose structural implementation lives in pre-existing files (shipped under a sibling requirement), `IMPLEMENTS` links are backfilled onto those pre-existing artifacts of record, bounded by the requirement's concrete subject matter.
   - `TESTS` → test files that verify the requirement
   - `DOCUMENTS` → ADRs or design docs that explain the approach (also used for forward-looking requirements that the diff references but does not yet ship)

Before you stop, run `make policy` alongside the feature-specific verification commands. This catches ADR drift, missing controller/MCP/doc parity, migration companion updates, and PR body omissions before review.

### Record Architectural Decisions

When implementation involves a design choice, create an ADR:

```
gc_create_adr(
  uid: "ADR-030",
  title: "On-prem Hetzner Deployment",
  decision_date: "2026-05-03",
  context: "Need a deployment target that lifts the JVM memory ceiling and removes the AWS account dependency",
  decision: "Run the docker-compose stack on red-dragon, tailnet-only, image pulled from GHCR",
  consequences: "Eliminates EC2/EBS/S3/DLM/IAM surface; capacity headroom for AGE and the embedding pipeline; no marginal cost"
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

Asset topology supports cycle detection and impact analysis ("what services are affected if this database goes down?").

## Phase 4: Verify & Ship

### Quality Gate Evaluation

Before merging, evaluate all quality gates:

```
gc_evaluate_quality_gates(project: "my-system")
```

Returns overall pass/fail + per-gate details (actual value vs. threshold). Fix failures: write missing tests, link orphaned requirements, clean up duplicates.

This evaluation is also wired into the `/implement` completion gate: `gc_assert_quality_gates(project: "my-system")` wraps the same server-side evaluation and refuses (`ok: false`) when any enabled gate fails, returning only the failing gates as `{name, metric_type, threshold, actual}` so the run is blocked with an actionable error before the change is committed.

### Architecture + Review Pipeline

Per issue #804, the `/implement` skill runs one mandatory Codex architecture preflight before coding and then a small set of independent verification/review stages before the PR is presented for human review:

1. **Codex architecture preflight** (Step 2.5): cross-cutting concerns, reuse opportunities, abstraction/concept confusion, ADR/design guidance when needed.
2. **Pre-push Codex review** (Step 6.5, default cap 1; configurable via `workflow.codex_review.pre_push_cap` in `.ground-control.yaml`). Production-readiness review (`gc_codex_review`, core + security reviewers) runs against the staged + unstaged diff *before* the first push, so each fix iteration is local (~5 min) instead of a CI/SonarCloud roundtrip (10-15 min). Every successful cycle posts a verbatim findings record to the resolved issue thread (durable per ADR-029) plus inline PR review comments when a PR exists. The cap default dropped from 3 to 1 in issue #906; cycles 2 and 3 empirically compound the agent's own fix-introduced bugs more than they catch net-new bugs, and CI / SonarCloud / the human reviewer cover the residual risk; repos that want the historical cap-3 behavior set the knob explicitly.
3. **Pre-push test-quality review** (Step 6.6, default cap 1; configurable via `workflow.test_quality_review.pre_push_cap`). Moved pre-push by issue #906 from the former post-PR Step 13. `gc_test_quality_review` (MCP tool) catches assertion-free tests, mock-only assertions, integration-as-unit tests, and tests that can't detect regressions. The tool shells out to `claude --print --model claude-sonnet-5` with the canonical rubric, parses structured JSON findings, posts the durable record + cycle marker to the issue thread, and returns a `{findings, cycle, cap, next_action, ...}` envelope. The parent /implement workflow reads `next_action` as a directive. Per #884 v2 this replaces the prior `Skill("review-tests")` boundary, which produced prose findings the parent kept echoing back to the user instead of fixing in-turn. Cycle cap is server-side and lane-agnostic. Authentication: the wrapper strips `ANTHROPIC_API_KEY` so claude uses the host's OAuth session. Full mechanism in `architecture/notes/test-quality-review-engine.md`.
4. **CI + SonarCloud** (Steps 10 / 11): static analysis, coverage, duplication, security hotspots run after push. With both AI-assisted reviewers clean pre-push, the PR opens for human review only after every mechanical and AI-assisted check has passed; there is no post-push review fix loop (former Step 13 / Step 14 were merged out by #906).

The post-push codex review (former Step 12) was removed by issue #804: the pre-push pass catches everything codex would normally flag, and merge-commit drift is the responsibility of CI (compile/tests/integration) and SonarCloud (quality), not a duplicate codex run. The post-push tool entrypoint (`gc_codex_review` with a `pr_number`) remains as defense-in-depth for direct callers but the SKILL no longer drives it.

**Reviewing a diff too large for one prompt (issue #1414).** When the diff exceeds `GC_CODEX_REVIEW_MAX_DIFF_BYTES` (default 256 KiB, `0` disables), the MCP server splits it into bounded inline slices and runs both reviewers over every slice as **one logical cycle**; slices are never counted as cycles and there is no per-slice marker. Previously the diff was replaced by a file manifest with an instruction to fetch per-file diffs via the reviewer's own shell tool; nothing verified that fetch, and a `ship` verdict caveated on the manifest alone was recorded as a clean pass. The cycle envelope now reports `diff_mode` (`inline` | `manifest`) and a bounded `review_coverage` summary, and a cycle that does not achieve complete coverage fails closed (`status: "post_failed"`, `error: "review_coverage_incomplete"`) without writing a findings record, decision record, or cycle marker, so no cycle is consumed and the retry is free.

**Automated cap disposition (optional, default off; issue #1245).** By default, a cap hit with findings (after the last-in-cap findings are fixed and self-verified) stops the run and asks the user whether to spend one more review cycle. When `workflow.review_disposition.enabled` is true, the orchestrator instead calls `gc_review_cap_disposition`, which scores the post-fix diff server-side (diff size, changed-surface class, finding shape, prior auto-overrides) and returns `proceed`, `one_more_cycle`, or `escalate_to_human`. A hard `max_auto_overrides` ceiling (default 1) bounds the auto path so it can never grant a second over-cap cycle (the anti-runaway guarantee #906 was built for is preserved), and authority for the one auto cycle is a durable `gc:review-auto-disposition` marker, not agent text. `mode: shadow` (the enabled default) posts the disposition but still escalates while agreement data accrues; `mode: authoritative` lets it drive control flow. With the knob off, behavior is byte-for-byte unchanged. Enforced in the MCP layer (GC-O007; ADR-031 / ADR-029 amendments).

**PR title format (issue #901).** Step 9 validates the title locally and
`gc_create_synchronized_implement_pr` validates it again at the GitHub-write
boundary: a single conventional-commit type with optional scope
(`<type>(<optional-scope>): <subject>`, no compound `security/docs:` prefixes)
and a lowercase-leading subject. Per-repo `workflow.pr_title` overrides remain
authoritative.

All findings are fixed before the PR is presented for human review. "Defer" is not a valid disposition (ADR-029) and is mechanically enforced (issue #830): the `.claude/hooks/block-defer-language.py` PreToolUse hook blocks GitHub issue/PR text carrying deferral-disposition language, and `bin/policy` flags it in the PR body at completion gate. The only valid dispositions are `fix`, `wontfix` (with explicit user authorization), or `not-applicable` (with rationale); filing a tracking issue does not make a deferral valid. Codex review classifies each finding `one-off` or `class`; a `class` finding is fixed at the category level (one structural point of repair applied to every instance), not site-by-site.

### Impact Analysis

Before shipping, understand the blast radius:

```
gc_analyze_impact(uid: "GC-R001")
```

Returns all transitively affected requirements: everything upstream and downstream that could be impacted by a change to this requirement.

## Phase 5: Release & Audit

### Release Notes via Release Please

`CHANGELOG.md` and the product SemVer are owned by Release Please (GC-P027, issue #1399), not by a per-PR fragment convention - feature PRs neither edit `CHANGELOG.md` nor file a `changelog.d/` fragment; that convention is retired. CI enforces a Conventional Commit PR title (`.github/workflows/pr-title.yml`); Release Please parses the resulting commit history on `main` and maintains a `chore(main): release X.Y.Z` PR that regenerates `CHANGELOG.md` and bumps the product-version mirrors. A maintainer cuts the release by merging that PR - never by hand-tagging or hand-editing `CHANGELOG.md`. See `docs/DEVELOPMENT_WORKFLOW.md § Release model` for the full mechanics.

### Create a Baseline

Freeze the requirement set at a release milestone:

```
gc_create_baseline(name: "v1.0", project: "my-system")
```

Baselines capture the Envers revision at creation time. `gc_get_baseline_snapshot` reconstructs the full requirement set as it existed at that point, without maintaining separate copies.

### Compare Releases

```
gc_compare_baselines(baseline_id: <v1>, other_baseline_id: <v2>)
```

Shows added, removed, and modified requirements between two baselines. Essential for release notes, change impact assessment, and regulatory audits.

### Audit Trail

Every change to every entity is versioned:

- `gc_get_requirement_history`: all revisions with timestamps and actors
- `gc_get_requirement_diff`: structured diff between two revisions (per-field changes)
- `gc_get_timeline`: unified chronological stream of all changes to a requirement
- `gc_get_project_timeline`: same, across all requirements in a project
- `gc_export_audit_timeline`: CSV export for compliance reporting

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

- **Per-step model routing.** Each `/implement` step carries a capability tier (`low`, `medium`, `high`) and stable stage/purpose name. `gc_resolve_workflow_route` reads `.ground-control.yaml` and resolves advisory provider/model/tier metadata. The primary invocation session remains the normal executor for every driver; Ground Control no longer has an agent/fallback routing field and does not manufacture subagents for routine development. Opt-in per repo via `.ground-control.yaml`'s `routing.enabled` knob (default `false`) plus optional `routing.stages.<stage>` overrides.
- **Pre-PR remote-base synchronization (#1421).** Step 8.5 fetches the configured integration branch with an explicit remote-tracking refspec, merges it into the feature branch in the invocation checkout, mechanically runs completion and policy on the exact merged tree, verifies and publishes the resulting graph, and posts an idempotent trusted issue-thread attestation. Step 9 creates the PR only through `gc_create_synchronized_implement_pr`, which re-fetches the base and verifies the attestation, verified tree, local/remote feature heads, authorized repository, and any existing PR's complete identity immediately before the GitHub write. `/integrate` keeps its separate worktree/rebase lifecycle.
- **Mechanical execution bands (#1426/#1473).** `gc_implement_mechanical` composes successful-path primitives into six actions: `bootstrap` for Steps 1–2 context and pickup, `verify` for Step 6, `publish` for Steps 7–8.5, `monitor` for Steps 10–11, `readiness` for Step 17 pre-merge, and `finalize` for Step 17 post-merge plus Step 20. The tool invokes no model. The canonical workflows start `verify`, `publish`, and `monitor` with `async: true` plus one bounded `idempotency_key` per logical attempt, then poll `gc_codex_job`; this lets repository commands and remote watchers outlive one MCP request. Identical starts reuse one job, conflicting key reuse is rejected, and `verify`/`publish` are single-flight per checkout. A completed job's `result` is the unchanged mechanical envelope, so an expected gate failure is `status: "done"` with `result.ok: false`, `agent_required: true`, and bounded repair evidence. `bootstrap`, `readiness`, and `finalize` remain synchronous. Mechanical jobs report `job_not_cancellable` rather than claiming abort they do not implement. Architecture, implementation, finding decisions, merge-conflict resolution, and traceability reconciliation remain agent work.
- **Cursor CLI skill discovery (issue #1189 / #1191).** `/implement` and `/quickfix` are available from Cursor Agent chat and CLI after the same host bootstrap as Claude Code and Codex: `bin/install-skills.sh` hard-copies `skills/<name>/` into `~/.cursor/skills/<name>` (symlinks fail Cursor's root check; see `scripts/test-cursor-skill-symlink.sh`). Ground-Control repos also ship `.cursor/skills/implement/SKILL.md` as a real wrapper file for in-repo discovery.
- **Durable-record MCP tools.** Three new deterministic tools replace agent-authored long-form comments. `gc_post_decision_record` renders the canonical Step 6.5 decision-record from structured findings; `gc_post_final_report` renders the Step 17 final summary (invoked via `gc_assert_completion`); `gc_render_pr_body` composes a PR body that satisfies `check_pr_body`'s policy gates from structured input (`change_class ∈ {doc-only, source, source+migration}` shapes the integration-tests / changelog-fragment cells). Its evidence envelope is repo-neutral (issue #1199): every attestation names a gate the workflow enforces for all repositories, never a stack-specific check (Java/domain rules, Envers, framework layering) or a configured command string. All three filter sensitive content, post under a structured marker family (`gc:decision-record`, `gc:final-report`), and reject `decision: "defer"` server-side. For `/implement`, `gc_post_final_report` now requires `plain_english_outcome` and renders it as an Outcome section before the structured evidence.
- **Traceability + post-merge reconciliation/close gates (#1058/#1156/#1103/#963).** `gc_assert_completion` takes a `phase` parameter. `phase="post_merge"` (the Phase E, default form) is **merge-gated** - it refuses with `completion_pr_not_merged` unless the linked PR is merged - then sequences `gc_assert_traceability_reconciled` and `gc_post_final_report` in one deterministic call (the reconciliation assertion posts the `traceability_reconciled` phase marker server-side; `gc_post_final_report` refuses without it). `project` is optional on these calls: explicit override wins, otherwise the MCP layer infers from `repo_path`'s `.ground-control.yaml`, and when neither yields a project the backend remains authoritative for multi-project `project_required` responses (#1462). `phase="pre_merge"` is the Phase D terminal: it posts a readiness record carrying a `ready_for_review` marker (no `gc:final-report` marker) but NOT traceability (the requirement is still DRAFT pre-merge), and still enforces CI/Sonar/review/scrub gates. Per issue #963 the requirement `DRAFT→ACTIVE` transition (Step 15), traceability reconciliation (Step 16), and the reconciled final report (Step 17 `post_merge`) all run in **Phase E, after the PR merges** - never pre-merge - so Ground Control state never runs ahead of shipped code. `gc_close_issue_after_merge` is the canonical close path used by Phase E (Step 20): it verifies the linked PR's `merged_at` non-null AND state `MERGED` before running `gh issue close`, is idempotent on already-closed issues, and performs only linked-PR resolution, merge-state verification, and closure - no next-issue recommendation (ADR-089). The PR-body `Closes #<n>` keyword stays as the GitHub UI cross-link but is no longer load-bearing for the close.
- **Per-step telemetry.** `gc_log_step_telemetry` records one durable observation per routed step. Since issue #1354 (ADR-090 amendment) this is a **queryable per-step record on the ADR-061 `workflow_run` projection**, not a gitignored `.gc/telemetry/*.jsonl` file: the tool upserts the run by its `(project, repo, issue, branch)` natural key and appends a phase-event row distinguished by the `ADR036_STEP_JSONL` emitter, keyed on work item, run, catalogue station (resolved backend-side from the `stage`), and capability tier. Its `outcome` stays operation outcome (`station_result` is `UNOBSERVED`), so lifecycle hot-spot, yield/rework, and context-graph consumers exclude it while the per-run event surface exposes it. Operational measurement only: never workflow state, never a cycle counter, never compliance evidence. The write is strictly fail-open with no local-file fallback. Opt-in via `.ground-control.yaml`'s `telemetry.enabled` knob (`gc_log_step_telemetry` refuses with a structured `telemetry_disabled` envelope when the knob is off, so callers cannot bypass the opt-in). Pre-existing `.gc/telemetry/*.jsonl` files are inert historical artifacts; nothing writes them, and the local summarizer that read them was removed in #1507.

These tools are deterministic, structured-input-in, structured-output-out, with no LLM call in the tool itself.

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
`gc_test_quality_review`) return a verdict envelope with `verdict`,
`architectural_read`, `blocking`, and capped `notes` - instead of a
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

**2026-07-30 (issue #943).** The two public review-cycle wrappers are now
async-only and require one bounded `idempotency_key` per logical attempt.
Identical retained retries return the same running or terminal job, changed
input conflicts, and distinct keys are single-flight per canonical repository,
issue, and reviewer. Cycle jobs no longer claim cancellation as rollback.
After `job_not_found`, the workflow refreshes the issue thread before choosing
a new key because the process-local handle may disappear after durable records
landed.

**2026-05-26 (issue #989).** A new workflow lane, `/integrate`, prepares maintainer-approved pull requests against the latest base branch of a target repository (label-based discovery, rebase, completion gate, CI/Sonar watch, force-with-lease push). The lane is prepare-only by default: it does not merge. The GC-O007 single-merge-touchpoint contract is unchanged. See `docs/DEVELOPMENT_WORKFLOW.md § /integrate` and GC-O011.

**2026-05-26 (issue #989 merge carve-out).** The single-human-touchpoint contract is amended to permit `gc_integration_manager` action=prepare mode=merge to execute the merge for queue entries that the same lane has just prepared (rebased, completion-gate green, CI green, Sonar green). The carve-out is narrow: merge is only legal when invoked through the integration manager's MCP tool boundary, only on PRs the same run has marked outcome=ready, and only when the repository has opted in via `workflow.integration_manager.merge_strategy`. All other agent paths to merge remain forbidden by skill prose and by the `.claude/hooks/git-merge-guard.py` PreToolUse hook that already blocks `gh pr merge` and `git merge` from agent Bash invocations. The MCP server itself is the only privileged-side-effect surface that can execute the merge; the hook layer does not apply to MCP server subprocesses, so the access-control surface is the gc_integration_manager tool registration.

**2026-07-15 (issue #1382 base-to-feature maintenance merge).** `.claude/hooks/git-merge-guard.py` now permits an agent to merge the integration branch (`origin/dev`) into the current non-protected feature branch - branch maintenance that keeps an open PR current, completed with real conflict resolution plus an ordinary `git commit` - while continuing to block `gh pr merge`, protected-branch-destination merges, non-`origin/dev` sources, and every ambiguous invocation shape. This qualifies the 2026-05-26 (#989) note above that the hook blocks all agent `git merge` calls: it now blocks pull-request and protected-branch merges, not the base-to-feature maintenance merge. The single-merge-touchpoint contract (PR merge) is unchanged; the integration-manager carve-out remains the only automated PR-merge path. See ADR-029 (amendments) and `docs/DEVELOPMENT_WORKFLOW.md § Git Merge Guard`.

**2026-07-25 (issue #1416 execution contract).** `/implement` begins by loading
its canonical development principles and pins an immutable execution contract
for the run. Issue-branch preparation stays in the checkout where the command
was invoked; `/implement` does not create worktrees. Problems discovered during
the run are fixed and verified regardless of provenance or anticipated scope,
or are retained as durable open obligations when a permitted pause is required.
Open obligations block both readiness and completion. Local verification is
risk-proportionate: batch related edits, use targeted tests during
implementation and review fixes, widen for shared/security-sensitive risk, and
run broad completion/policy gates once at the meaningful final tree boundary.
Pre-commit, completion, review, CI, SonarCloud, and final policy gates remain
mandatory; efficient iteration does not waive them.

**2026-07-29 (issue #1476 unobserved review stations).** A review station that
runs but renders no verdict - a timed-out engine, a dead child process, an
unparseable payload, an incomplete slice sweep - is a missing observation, not a
failing gate and not a defect. Previously its only exit was a repository writer
posting an exact `wontfix` authorization for a defect nobody had observed.

Each such failure returns before any findings record or cycle marker is written,
so the review cap is untouched and re-running is free. The station owner now
consumes that free retry: `workflow.codex_review.non_verdict_retry_limit` and
`workflow.test_quality_review.non_verdict_retry_limit` set how many additional
attempts follow the first (bounds `[0, 2]`, default 1). Only the declared
transient classes retry; cancellation, cap refusal, invalid input or
configuration, authorization failure, reserved-marker or sensitive-content
rejection, and GitHub posting failure do not.

The first non-verdict attempt opens a `station_observation` execution
obligation - one per issue, station, and logical cycle, so repeated transport
attempts update one record. A later attempt that renders a verdict resolves it
as `reobserved`, bound to the findings record proving the verdict exists, and
written between that record and the cycle marker so the cap is never spent while
the obligation is still open. `reobserved` states only that the gate was
observed: a re-observed verdict that found problems leaves every finding under
the existing `fix` / `wontfix` / `not-applicable` rules.

The disposition is tool-attested, not agent-asserted.
`gc_record_execution_obligation` does not accept it, and replay honours it only
when the marker author is the trusted MCP posting identity, the obligation is a
`station_observation` for the same station and logical cycle, and the referenced
record was posted by that identity. Anything else leaves the obligation open and
completion blocked. Exhausted re-attempts keep the obligation open and escalate
under `hard_external_dependency` naming the station, never as a `wontfix`
decision request. Records use the new `gc.implement.execution-obligation/v2`
marker family; v1 obligations keep their existing semantics and authorization
checks. The single-human-touchpoint contract and review caps are unchanged. See
ADR-029 and ADR-031 (amendments).
