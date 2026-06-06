# Development Workflow

This documents the automated development workflow using Claude Code with the `/implement` skill. The workflow takes a Ground Control requirement from plan through PR-ready with a single skill invocation.

## Prerequisites

### GPG Signing
- GPG key `B47C8B1F62CC2B54` has no passphrase (removed 2026-03-31)
- Commits are signed non-interactively by Claude Code
- Global deny rules and blocking hooks were removed to enable this

### OpenTelemetry Observability
- OTEL collector runs as a Docker container at `~/.claude/telemetry/`
- Config: `~/.claude/telemetry/otel-collector-config.yaml`
- Compose: `~/.claude/telemetry/docker-compose.yml`
- Output: `~/.claude/telemetry/data/claude-code.jsonl`
- Rotation: 100 MB max, 90-day retention, 10 backups
- Start: `cd ~/.claude/telemetry && docker compose up -d`
- Analyze: `~/.claude/telemetry/claude-metrics`

Env vars in `~/.claude/settings.json`:
```
OTEL_LOGS_EXPORTER=otlp
OTEL_LOG_TOOL_DETAILS=1
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

### Codex CLI
- OpenAI Codex CLI (`codex-cli`) installed at `~/.nvm/versions/node/v25.8.1/bin/codex`
- Used for architecture preflight and cross-model code review via Ground Control MCP workflow tools

## Workflow: `/implement <issue-number | requirement-uid>`

Every `/implement` run is driven by a GitHub issue. The issue is the durable artifact that records why the change is being made, which requirements are in scope (if any), and what acceptance looks like. You invoke the skill in either of two ways:

- **`/implement 123`** or **`/implement #123`**: implement GitHub issue #123 in the current repo. The issue body may declare in-scope requirements under a `## Requirements` section (a bulleted list of UIDs). The skill parses that section and carries the list through clause verification, traceability reconciliation, and status transitions. If the section is absent or empty, the run is treated as a bug fix / refactor / maintenance change with no formal requirements; traceability is still reconciled against the diff, but no requirement is transitioned to `ACTIVE`.
- **`/implement GC-X042`**: implement a requirement by UID. The skill finds the open GitHub issue linked to that requirement via traceability (`artifact_type: GITHUB_ISSUE`); if no such issue exists, it creates one via `gc_create_github_issue` and adds the UID to its `## Requirements` section. From that point forward the run is identical to the first form: the issue becomes the authoritative input.

Grouped implementation (shipping several related requirements in one PR) is expressed by listing all of them under `## Requirements` in a single issue body. One issue → one `/implement` run → one PR → N requirements transitioned to `ACTIVE` in the same commit stream. Do NOT spin up one issue per requirement when they belong together; the grouping is what makes the review boundary coherent.

Repo-local Ground Control project context comes from a `.ground-control.yaml` file at the repo root (with larger rule files under `.gc/`), not from `AGENTS.md` inline YAML or hardcoded assumptions in the skill. The workflow validates this via `gc_get_repo_ground_control_context` before it starts implementation; that call returns the project id, workflow commands, remote-quality settings, and plan rules in a single response. It should:
- use the repo's configured Ground Control `project` when present
- treat inputs like `OBS-001`, `DSL-101`, `API-412`, or `GC-J001` as already-complete UIDs
- avoid guessing a prefix from the repository name

Recommended `.ground-control.yaml` convention:

```yaml
schema_version: 1
project: aces-sdl
github_repo: owner/repo

workflow:
  test_command: make test
  completion_command: make check
  lint_command: make lint
  format_command: make format
  base_branch: dev
  engine:
    version: "^1.0.0"
  gate_manifest: .gc/gates.yaml
  packs:
    - id: docs-generic
      version: "^1.0.0"
      scope: .
      profile: default
  gate_overrides:
    docs.docs_policy.threshold.max: 0

sonarcloud:
  project_key: Owner_Project
  organization: owner

rules:
  plan_rules: .gc/plan-rules.md

knowledge:
  dir: docs/knowledge
  schema: docs/knowledge/SCHEMA.md
  inbox: docs/knowledge/inbox

docs:
  adr_dir: architecture/adrs/
  architecture_overview: docs/architecture/ARCHITECTURE.md
  coding_standards: docs/CODING_STANDARDS.md
  workflow_reference: docs/DEVELOPMENT_WORKFLOW.md
  knowledge_base: docs/knowledge/

example_paths:
  source: src/
  test: tests/

requirements:
  uid_examples:
    - GC-X001
    - OBS-042

cross_cutting_concerns:
  description: |
    Logger: <project logging convention>
    Validation: <schema / validation convention>
    Errors: <error envelope / handler>
    Tests: <fixture and test-slice patterns>

routing:
  enabled: false
  default_provider: claude
  default_fallback: parent
  stages:
    implementation:
      tier: medium
      provider: claude
      model: claude-sonnet-4-6
      agent: subagent
      fallback: parent

telemetry:
  enabled: false
```

Config contract:

- `schema_version` is required and currently must be `1`.
- `project` is required and must be a lowercase identifier using letters, numbers, and hyphens.
- Unknown top-level keys are rejected. Current top-level keys are `schema_version`, `project`, `github_repo`, `workflow`, `sonarcloud`, `remote_quality`, `rules`, `knowledge`, `docs`, `example_paths`, `requirements`, `cross_cutting_concerns`, `routing`, `telemetry`, and `architecture`.
- Legacy `workflow.test_command`, `workflow.completion_command`, `workflow.lint_command`, and `workflow.format_command` values are optional non-empty strings. `workflow.base_branch` must be a safe Git ref name using `[A-Za-z0-9._/-]`.
- `workflow.engine.version` declares the portable workflow engine version constraint. It is optional; omitted means the repo has not selected versioned engine assets.
- `workflow.gate_manifest` points to the repo-relative gate manifest. Omitted means `.gc/gates.yaml`.
- `workflow.packs[]` declares selected gate packs with `id`, `version`, `scope`, and optional `profile`. Pack scopes are repo-relative and containment-checked.
- `workflow.gate_overrides` is a mapping from dotted gate override keys to scalar values. It is used for repository ratchets such as `backend.mutation.threshold.min: 60`.
- `sonarcloud` is optional, but when present it must include non-empty `project_key` and `organization`.
- `remote_quality` is optional. `tier` defaults to `platform_minimum`; repos can ratchet to `zero_overall_issues`. Optional `min_coverage` and `max_duplications` thresholds are numbers in `[0, 100]`.
- `rules.plan_rules` is optional and points to the repo-relative plan-rules file whose content is inlined into `gc_get_repo_ground_control_context`.
- `knowledge.dir` is required when `knowledge` is present. `knowledge.schema` and `knowledge.inbox` are optional overrides; by default they resolve under `knowledge.dir`.
- `docs.*` and `example_paths.*` are optional repo-relative paths. Docs paths are containment-checked so a config file cannot point an agent outside the repository.
- `requirements.uid_examples` is optional and must be a list of non-empty strings.
- `cross_cutting_concerns.description` is optional free text shown to agents during planning.
- `routing.enabled` defaults to `false`. When enabled, omitted `/implement` stages use built-in defaults; `routing.stages.<stage>` overrides a specific stage/purpose route.
- Routing stages use lowercase stage keys matching `[a-z][a-z0-9_-]*`. Route fields are `tier`, `provider`, `model`, `agent`, and `fallback`.
- Routing `tier` is one of `low`, `medium`, or `high`; `provider` currently supports `claude`; `agent` is one of `parent`, `subagent`, or `cli`; `fallback` is one of `parent`, `error`, or `skip`.
- Claude model values in executable routing config must be canonical CLI ids such as `claude-haiku-4-5`, `claude-sonnet-4-6`, or `claude-opus-4-7`; display aliases like `sonnet-4.6` are rejected.
- `telemetry.enabled` defaults to `false`. `gc_log_step_telemetry` refuses to write telemetry unless this is explicitly true.

`AGENTS.md` should still carry a brief `Ground Control Context` section that points agents at `.ground-control.yaml` and `.gc/`, so repo newcomers know where the workflow config lives.

### Gate Engine Core

The MCP server exposes the portable gate engine through `gc_run_gates`. The tool is the execution boundary for local gates: agents do not parse `.gc/gates.yaml`, choose provider commands, compute diff hashes, or write gate markers in prose.

`gc_run_gates` input:

```json
{
  "repo_path": "/repo",
  "issue_number": 1075,
  "base_ref": "origin/dev",
  "head_ref": "HEAD",
  "phase": "local",
  "capabilities": ["policy", "unit_tests"]
}
```

The tool performs these steps:

- Resolves `.ground-control.yaml` through `gc_get_repo_ground_control_context`.
- Loads `workflow.gate_manifest` or `.gc/gates.yaml`.
- Validates the manifest and `.gc/workflow-lock.json`.
- Computes changed files and `diff_hash` with `git diff <base_ref>...<head_ref>`.
- For local completion runs that post a marker, refuses unless a fresh `impl_green`
  marker is bound to the current diff.
- Runs the ADR-057 assurance classifier against changed files before executing
  gates. L1/L2 surfaces must have a posted `contract` marker plus pack-detected
  contract and test artifacts; L2 also requires property-verification evidence.
  `docs-generic` no-ops on docs-only diffs.
- Selects gates by capability, scope, and `applies_when.paths`.
- Runs each command in its declared `cwd` with a bounded timeout and sanitized environment.
- Parses JSON provider output only when the gate declares `output.type: json`; otherwise the exit code is authoritative.
- Evaluates typed thresholds.
- Writes gate-effectiveness telemetry under `.gc/telemetry/gate-effectiveness-<issue>.jsonl`.
- Writes `gates_green` only when every applicable blocking local gate is satisfied.

Remote status gates run through `gc_watch_required_statuses`. That tool consumes `remote_status` manifest gates, watches the provider status rollup, and then verifies configured provider-quality substance server-side before writing `remote_gates_green`. For SonarCloud this includes quality-gate status, new and overall issues by severity, reliability/security/maintainability ratings, security hotspots, coverage, and duplications. A green PR checkmark alone is not sufficient.

Gate-effectiveness telemetry is analytic: `gc_gate_telemetry_summary` aggregates per-gate fire rate, outcomes, override/false-positive/escape rates, and duration. It never feeds back into in-run gate counters.

When a repo has no manifest but still declares legacy `completion_command`, `test_command`, `lint_command`, or `format_command`, `gc_run_gates` synthesizes temporary `policy`, `unit_tests`, `lint`, and `format` gates. The result envelope sets `legacy_mode: true`, records telemetry, and leaves `pack_versions` empty. Legacy mode preserves compatibility but does not claim pack coverage.

#### Gate Pack Registry

Gate packs live under `workflow/packs/<pack-id>/`. This directory is separate
from the OSCAL control-pack catalog under `packs/`.

Each gate pack contains:

- `pack.yaml`: pack id, version, engine compatibility, profiles, threshold
  tier ownership, install templates, and self-test metadata.
- `capabilities.yaml`: every engine capability marked as `provided`,
  `provider_missing`, or `not_applicable`.
- `classifier.yaml`: deterministic ADR-057 surface detection and artifact
  patterns for the pack's language/framework ecosystem.
- `templates/`: pack-owned config files copied into `.gc/gate-packs/<id>/`.
- `installer.mjs`: a pack-local installer shim.
- `selftest/`: a generated fixture contract and runnable self-test.

The catalog at `workflow/gate-catalog.json` resolves the seven initial packs:
`rust-cargo`, `python`, `jvm-gradle`, `jvm-maven`, `node-ts`, `cpp-cmake`, and
`docs-generic`. Catalog entries carry the exact version, source path,
compatible engine range, SHA-256 checksum, signer placeholder, and trust
policy. Checksum verification is enforced today. Release signatures and
provenance are recorded as `TODO` metadata until signed pack artifacts exist.

`gc_install_workflow_assets` installs a pack into a consumer repository:

```json
{
  "repo_path": "/repo",
  "pack_id": "node-ts",
  "version": "^1.0.0",
  "scope": "frontend",
  "profile": "react-vite",
  "install_dependencies": true,
  "run_selftest": true
}
```

The installer resolves the catalog entry, verifies the pack checksum, vendors
the exact pack under `.gc/vendor/ground-control/packs/<pack-id>/<version>/`,
copies templates, writes or merges `.gc/gates.yaml`, writes
`.gc/workflow-lock.json`, updates `.ground-control.yaml` with
`workflow.engine`, `workflow.gate_manifest`, and `workflow.packs[]`, optionally
adds declared dev dependencies through the detected package manager, and runs
the pack self-test. It leaves normal repository file changes for review.

Self-tests create a temporary fixture repository, install the pack, validate
the generated manifest, run one passing gate, run one intentional failing gate
fixture, and verify the pack's `provider_missing` or `not_applicable`
behavior. If the required toolchain is missing, the self-test exits
successfully with `status: "skipped"` and a concrete missing-tool reason.

#### Gate Manifest

The manifest is a strict YAML object. Unknown keys are rejected at every level. Gate ids are globally unique. Every `capability` must be one of the engine vocabulary values from ADR-062:

```text
format, lint, build, type_safety, unit_tests, integration_tests,
contract_boundary, property_verification, architecture, complexity,
mutation, diff_coverage, sast, secret_scan, dependency_policy,
accessibility, docs_policy, traceability, policy, remote_status
```

Minimal manifest:

```yaml
schema_version: 1
engine:
  min_version: ">=1.0.0"
  manifest_version: "1.0.0"

defaults:
  timeout_seconds: 900
  provider_missing: reviewer_fallback
  fail_fast: false

packs:
  - id: docs-generic
    version: "1.0.0"
    scope: .

gates:
  - id: docs.docs_policy
    capability: docs_policy
    pack: docs-generic
    cwd: .
    command: make policy
    blocking: true
    scope: repo

  - id: src.unit_tests
    capability: unit_tests
    pack: example-pack
    cwd: .
    command: npm test -- --json
    blocking: true
    scope: changed
    applies_when:
      paths:
        - "src/**"
    output:
      type: json
      metrics:
        passed_tests: summary.passed
    threshold:
      metric: passed_tests
      min: 1
```

Path-valued fields are repo-relative and containment-checked: `cwd`, pack `scope`, `artifacts`, `config_paths`, `generated_files`, and `output.path`. `applies_when.paths` are repo-relative glob patterns evaluated by the MCP server against the changed-file set. Supported gate scopes are `repo` and `changed`.

Thresholds are typed by `metric` and may include `min`, `max`, `break`, `severity`, or `policy`. Numeric thresholds compare numeric provider output. Severity thresholds use the ordered set `info`, `low`, `medium`, `high`, `critical`, `blocker`. Policy thresholds compare exact strings.

Provider-missing behavior is explicit. A gate with no command returns a `provider_missing` result. It can satisfy the local gate run only when the gate is non-blocking, declares `provider_missing: reviewer_fallback`, or declares `provider_missing: not_applicable`. Reviewer fallback records `reviewer_fallback_used`; it does not claim that a deterministic provider passed.

`.gc/workflow-lock.json` is required when a manifest exists. It records the exact engine and pack versions used by the manifest:

```json
{
  "schema_version": 1,
  "engine": {
    "version": "1.0.0",
    "compatible": ">=1.0.0 <2.0.0"
  },
  "packs": [
    {
      "id": "docs-generic",
      "version": "1.0.0",
      "checksum": "sha256:<hex>",
      "source_url": "workflow/packs/docs-generic",
      "compatible_engine": ">=1.0.0 <2.0.0",
      "signer": "TODO: release signer",
      "trust_policy": "checksum-only-development",
      "installed_at": "2026-06-06T00:00:00.000Z"
    }
  ]
}
```

#### Contract-First Markers and Assurance Classifier

The local `/implement` marker chain is:

```text
context_loaded -> preflight -> contract -> plan -> test_red -> impl_green -> gates_green -> remote_gates_green -> traceability_reconciled
```

`gc_get_implementation_context` writes `context_loaded` after loading the binding ADRs, cross-cutting-concern incumbents, existing IMPLEMENTS artifacts, and the related requirement neighbourhood. `gc_post_interface_contract` writes `contract` after re-verifying `context_loaded` and `preflight`.
The contract is the language-neutral public shape the implementation promises:
interfaces, signatures, DTOs, error envelopes, API shapes, and invariants. It
also injects the ADR-059 engineering contract. `gc_post_implementation_plan`
refuses without `context_loaded` and `contract`.

`gc_assert_test_red` writes `test_red` only after re-running a targeted command
and observing a failing test/contract check, or after validating a documented
non-executable carve-out. `gc_assert_impl_green` writes `impl_green` only after
re-running the targeted command and observing green implementation evidence, or
after validating the same carve-out. `gc_run_gates` refuses `gates_green`
without a fresh `impl_green` marker.

The assurance classifier is engine-generic and pack-specific. The engine owns
diff binding, marker lookup, artifact checks, and gate refusal. Each
`classifier.yaml` owns language/framework detection: for example JVM
`@PreAuthorize`, `canTransitionTo`, graph traversal, and transactional
mutators; Python auth decorators, Pydantic/Enum state, graph operations, and
SQLAlchemy mutators; TypeScript route guards/reducers/fast-check; Rust
`Result` boundaries, `unsafe`, and proptest; C/C++ status/result boundaries
and sanitizer/gtest patterns. Trivial DTOs/records/config/pure enums are
excluded by pack rules.

#### Marker Model

`context_loaded`, `contract`, `test_red`, `impl_green`, `gates_green`, `remote_gates_green`, and `traceability_reconciled`
are bound phase markers posted by MCP tools. The existing phase marker family
remains:

```html
<!-- gc:phase phase="gates_green" issue="1075" marker_schema="1" ... -->
```

`gates_green` binds to:

- issue number
- repository identity
- base ref
- head ref
- manifest hash
- diff hash
- pack-versions hash
- gate-result envelope id

`remote_gates_green` binds to:

- issue number
- pull request number
- head SHA
- manifest hash
- diff hash
- required-status-set hash
- provider-result-ids hash
- remote-quality hash
- remote-status envelope id

`traceability_reconciled` binds to the live diff hash and requirement set. `gc_post_final_report` refuses when the marker is missing or stale.

Downstream tools treat a marker as stale when any bound value no longer matches the current state. Stale marker refusals use `error: "stale_phase_marker"` and report the expected and actual binding values. Missing marker refusals use `error: "missing_phase_marker"`.

#### Required Remote Statuses

`gc_watch_required_statuses` is the provider-neutral remote gate watcher. It consumes `remote_status` gates from the manifest or an explicit `required_statuses[]` input, evaluates arbitrary status names, verifies provider-quality substance, and writes `remote_gates_green` only when both layers pass for the bound head SHA and diff. SonarCloud remains available as `gc_watch_sonar_analysis`, but that tool is an optional provider adapter, not a canonical workflow phase.

#### Traceability Reconcile And Assert

`gc_reconcile_traceability` computes `git diff --name-status` server-side, reverse-lookups traceability for each changed path, and returns a worklist plus the gap set of in-scope requirements without IMPLEMENTS coverage. The agent applies that worklist rather than hand-discovering changed artifacts. `gc_assert_traceability_reconciled` then recomputes the live diff, re-fetches requirements and artifact links, and writes `traceability_reconciled` bound to the diff hash. If HEAD moves, the marker is stale.

### User Touchpoint

Per ADR-029, the workflow has **one** synchronous human touchpoint: PR review and merge to `dev`. Plans are posted to the GitHub issue thread as comments and the agent proceeds without waiting; review findings and decisions on findings are also recorded on the issue thread. Everything before merge is automated.

### High-level flow

```mermaid
flowchart TB
  Start([/implement #issue or UID])
  S1[1 · Resolve issue · flag in-progress · parse Requirements]
  S2[2 · Read issue body + comments · context_loaded]
  S3[3 · Codex architecture preflight]
  S4[4 · Explore codebase + consult knowledge base]
  S4b[3.5 · Post interface contract · contract marker]
  S5[5 · Post plan as issue comment]
  S6[6 · TDD implementation · test_red + impl_green]
  S7[7 · pre-commit run]
  S8[8 · gc_run_gates · assurance classifier · gates_green]
  S8b[8.5 · Pre-push gc_codex_review · correctness + security + architecture · convergence dispatcher]
  S8c[8.6 · Pre-push gc_test_quality_review · test-strength · convergence dispatcher]
  S9[9 · Stage + commit + push]
  S10[10 · Create PR to dev]
  S11[11 · CI monitor]
  S12[11 · Remote quality substance · remote_gates_green]
  S15[15 · Transition + diff reconcile + traceability_reconciled]
  S18[18 · Report—DO NOT MERGE]
  End([User reviews PR and merges])

  Start --> S1
  S1 --> S2
  S2 --> S3
  S3 --> S4
  S4 --> S4b
  S4b --> S5
  S5 --> S6
  S6 --> S7
  S7 --> S8
  S8 --> S8b
  S8b --> S8c
  S8c --> S9
  S9 --> S10
  S10 --> S11
  S11 --> S12
  S12 --> S15
  S15 --> S18
  S18 --> End

  S8 -->|fail| S7
  S8b -->|findings, re-stage, re-run within cap| S6
  S8c -->|findings, re-stage, re-run within cap| S6
  S11 -->|red| S9
  S12 -->|findings or high_risk_fix| S9
  S15 -->|drift| S15

  classDef user fill:#fff7cc,stroke:#c9a900,color:#000
  class Start,End user
```

**How it reads:**

- **Yellow** nodes are user touchpoints. Per ADR-029, the workflow has **one** synchronous human touchpoint: PR merge (the `End` node). Plans are posted to the GitHub issue thread (S5) and the agent proceeds without waiting; review findings and decisions on findings are also recorded on the issue thread.
- **Entry is always by issue.** Step 1 resolves the input to a GitHub issue (either directly or via a UID → issue shim) and parses the `## Requirements` section from the issue body into `in_scope_requirements[]`. The list may be empty (bug fix / refactor) or contain one or many UIDs (grouped implementation). Everything downstream treats the issue as the authoritative context and the list as the set of requirements to be transitioned to `ACTIVE` on completion. Step 1 also creates the feature branch with a **bounded short-slug name**: `gh issue develop` is invoked with `--name <issue-number>-<short-slug>` (≤ 50 chars, ASCII-only); skipping `--name` lets `gh` slugify the full issue title and produces unusable 100+ character branch names that break terminal display, copy-paste, CI breadcrumbs, and downstream shell quoting. The skill then **validates the actual checked-out branch against the same rule**: `gh` reuses existing branches, so a previous pickup that ran before this rule existed (or didn't follow it) would otherwise hand the agent a non-compliant branch that flows through pickup comment, push, CI, and PR. The post-check fetches the configured base and compares against `origin/<base>` (local base can be stale); renames the branch in place when it has no commits relative to the remote base and no PR exists, or applies the in-progress signal first (so a paused picked-up issue stays visibly flagged) then stops and escalates to the user when a published PR is on the line. The post-check is the dispositive enforcement (the `--name` flag only governs first-time pickups). Slug derivation rule, validation predicate, and worked examples live in `skills/implement/SKILL.md` Step 1 sub-step 11. Step 1 then flags the resolved issue **in-progress**: an `in-progress` label (created on demand if the repo lacks it) plus a pickup comment on the thread recording the driver, the checked-out branch, and a timestamp; a maintainer scanning `/issues`, or another agent, sees at a glance that work is underway. The label is removed by Step 18 when the agent finishes its work and hands the PR to the user. The GitHub issue itself stays open until the user merges the PR, at which point GitHub closes it automatically via the `Closes #<issue-number>` keyword rendered into the PR body by `gc_render_pr_body` (Step 9). A run that escalates to the user without completing intentionally leaves both the label and the issue open, because the issue *was* picked up but the work is paused, not finished.
- **Steps 1–3.5** gather context, run the architecture preflight, inspect the codebase, and post the interface contract before any code is written. The contract is the oracle for the plan, targeted tests, and test-strength review.
- **Step 6** is TDD (red → green → refactor per clause) with tool-verified markers. `gc_assert_test_red` observes the failing targeted test/contract check, and `gc_assert_impl_green` observes the passing targeted check after implementation. Steps 7–8 are the local quality gate. A narrow documentation-only carve-out is documented in `skills/implement/SKILL.md` Step 4.4; both marker tools validate that carve-out before writing their markers. `gc_run_gates` then re-verifies fresh `impl_green`, runs manifest/legacy gates, and runs the assurance classifier before writing `gates_green`.
- **Step 8.5 (= SKILL Step 6.5)** is the pre-push Codex review pass per issue #804: `gc_codex_review` with `uncommitted=true` runs locally against the staged + unstaged diff and posts a verbatim findings record to the resolved issue thread for each cycle (durable per ADR-029). The Codex pass runs three fresh-context lenses with edit tools removed: correctness, security, and architecture. The cap is configurable per repo via `workflow.codex_review.pre_push_cap` in `.ground-control.yaml` (configured bounds `[1, 10]`, ADR-031 effective minimum cap 2) and enforced **per issue** (the cycle counter is anchored to the GitHub issue thread; the current branch is recorded in the marker for audit context but is NOT part of the cap key, so a branch rename on the same issue cannot reset the counter; see ADR-029). After a cycle's findings are surfaced, the agent **dispatches on the MCP-computed `next_action`**: `advance_to_next_phase`, `fix_findings_and_reinvoke`, `post_structured_decision_aid_and_escalate`, or `record_terminal_escalation`. Cycle 1 never collapses into escalation because findings exist; dirty cycles below the effective cap fix, self-verify, re-stage, and re-invoke. No commit/push between cycles. The post-push codex review (former Step 12 in earlier numbering) was removed by issue #804; merge-commit drift is the responsibility of CI (compile/tests/integration) and provider-neutral remote-quality gates.
- **Step 8.6 (= SKILL Step 6.6)** is the pre-push test-quality review, moved pre-push by issue #906 from the former post-PR Step 13. `gc_test_quality_review` runs locally against the same staged + unstaged + untracked diff as the test-strength lens. It consumes `gc_run_gates` mutation / diff-coverage results when the gate manifest supplies them, and provider-missing telemetry can route to reviewer fallback without treating the missing deterministic provider as passed. The cap is configurable via `workflow.test_quality_review.pre_push_cap` with the same ADR-031 effective minimum of 2. Same local-only convergence loop as Step 6.5 (re-stage, do NOT commit between cycles); same `gc_post_decision_record` contract for the durable record. The MCP tool returns a `{findings, cycle, configured_cap, cap, next_action, dispatcher, ...}` envelope; the parent /implement agent reads `next_action` as a directive, not as prose to summarize back to the user. Per #884 v2 this is an MCP tool, not a Skill; the v1 Skill-tool boundary returned prose findings that the parent's autoregressive "I just got a result, present it" bias kept echoing back to the user instead of fixing in-turn; the MCP boundary closes that bias structurally. See `architecture/notes/test-quality-review-engine.md` for the full mechanism (engine, auth, failure modes).
- **Steps 9–11** commit, push, open the PR, and block on CI plus remote-quality substance before any reviewer looks at the code. **PR title format (issue #901):** Step 9 validates the title locally before `gh pr create`. Two rules: (1) a single conventional-commit type with optional scope (`<type>(<optional-scope>): <subject>`); compound prefixes like `security/docs:` are rejected by `amannn/action-semantic-pull-request` and similar linters downstream repos run; for bundled PRs pick the dominant type and describe the rest in the subject; (2) the subject must start with a lowercase letter (`^[a-z].*$`); uppercase acronyms (NGFW, GCP, MCP) must be reshaped (lowercase, relocate into a slash-prefixed path, or front with a verb). Per-repo override via `.ground-control.yaml::workflow.pr_title.types` / `subject_pattern`; otherwise the conventional-commits canonical allow-list + `^[a-z]` pattern apply. Catching both locally removes the edit-cycle-per-failure cost the agent otherwise pays after every `gh pr create`. See `skills/implement/SKILL.md` Step 9 for the full rule + reshape examples.
- **Steps 13 / 14 were merged out by issue #906.** Test-quality review moved pre-push to Step 6.6; there is no separate post-PR review step. Final CI re-verify (former Step 14) collapsed into Step 10's existing CI watch on the original push; without a post-push fix loop there is no second CI run to re-verify. The numbering of Steps 15 / 18 / 19 (transitions, close, final report) is preserved so external references don't track a moving target; Steps 13 / 14 are intentional tombstones in SKILL.md, not errors.

#### Test-quality review engine

`gc_test_quality_review` shells out to the host's `claude` CLI:

```
claude --print
       --model claude-sonnet-4-6
       --output-format json
       --json-schema <findings schema>
       --add-dir <repo>
       --permission-mode bypassPermissions
       --allowedTools "Read Glob Grep"
```

with the prompt on stdin and `ANTHROPIC_API_KEY` **stripped from the subprocess env**. The strip is intentional: when the env var is set, `claude` uses it preferentially over the host's OAuth session; the env-var-anchored account is often empty (set up but never funded) while the OAuth account is what the user actually uses. Stripping forces OAuth—the canonical user-driven auth path that also powers the parent /implement run.

**Operator quickstart:**
1. Run `claude login` on the host once (credentials persist in `~/.claude`).
2. `/implement <issue>` invokes Step 6.6 automatically; no separate action needed.

**Model override:** pass `model` in the MCP call (`claude-haiku-4-5`, `claude-opus-4-7`, etc.). The /implement SKILL uses the default `claude-sonnet-4-6`.

**Separate billing account:** if the env-var path is preferred, remove the env-var strip in `runSingleClaudeTestQualityReview` (lib.js) and ensure `ANTHROPIC_API_KEY` has credits. The default strip path keeps OAuth as the canonical auth.

The legacy `Skill("review-tests")` path was removed in #884 v2. Existing host installs at `~/.claude/skills/review-tests/` and `~/.codex/prompts/review-tests.md` are orphaned and can be deleted manually; `bin/install-skills.sh` no longer installs them.
- **Step 15 transitions each delivered in-scope requirement to `ACTIVE`, runs `gc_reconcile_traceability`, applies the returned diff-derived worklist, and then calls `gc_assert_traceability_reconciled`.** The assertion recomputes the live diff and writes `traceability_reconciled` bound to the diff hash. Forward-looking requirements (the diff documents/references but does not deliver) stay DRAFT and use `DOCUMENTS` links instead. Runs with zero in-scope requirements still reconcile touched artifacts, because a bug fix may have touched files linked to other requirements whose links are now stale. Deleting the sole implementation of a requirement is escalated to the user rather than silently removing the link. Steps 16 and 17 are compatibility tombstones that redirect back to Step 15.
- **Every downstream failure loops back to step 9** (stage + commit + push), which is the single re-entry point for fix commits. The completion gate (step 8), the pre-push codex review (step 8.5), and the diff-bound traceability assertion (step 15) are the loops that target earlier steps, because they correspond to local-only / pre-PR / GC-only state respectively.

Claude does NOT merge. The user reviews the PR and merges.

## Per-step routing, tool surfaces, and telemetry (ADR-036)

Per ADR-036 the `/implement` skill carries three cost-side optimizations layered on top of the GC-O007 gate model (which is unchanged on the contract—one human touchpoint at PR merge, ADR-031's configurable convergence cap with effective minimum 2 and per-repo override via `workflow.codex_review.pre_push_cap`, zero deferral, four-phase structure).

| Optimization | What it changes | Opt-in knob |
|--------------|-----------------|-------------|
| Per-step routing | Each step carries a provider-neutral tier (`low`, `medium`, `high`); `gc_resolve_workflow_route` resolves the stage/purpose from `.ground-control.yaml` to a concrete provider, agent, canonical model id, and fallback policy. Claude Code routes subagent stages to canonical model ids such as `claude-haiku-4-5` and `claude-sonnet-4-6`; parent-only high-tier stages use `claude-opus-4-7`. Codex drivers ignore delegation today unless they explicitly call the resolver and external runner. | `.ground-control.yaml` → `routing.enabled` (default `false`) plus optional `routing.stages.<stage>` overrides |
| Durable-record MCP tools | `gc_post_decision_record` (Step 6.5 cycle decisions), `gc_post_final_report` (Step 19 summary), `gc_render_pr_body` (Step 9 PR body) replace agent free-prose with deterministic structured-input renderers. All three filter sensitive content, post under a structured marker family, and reject `decision: "defer"` server-side (see canonical succinctness rule in `skills/implement/steps/_review-loop-rules.md`). | Always available; SKILL calls them unconditionally once the tools are present |
| Traceability + post-merge close gates (#1058 / #1075) | `gc_get_implementation_context` writes `context_loaded` before contract/plan; `gc_reconcile_traceability` (Step 15) computes the live diff and returns the worklist; `gc_assert_traceability_reconciled` binds the marker to the live diff hash; `gc_post_final_report` (Step 19) refuses missing or stale traceability; `gc_close_issue_after_merge` (Step 20 / Phase E) verifies the linked PR's `merged_at` non-null AND state `MERGED` before closing the issue, idempotent on already-closed issues. The /quickfix lane is requirement-free and exempt from the traceability gate; a bounded `override_traceability_gate=true` + `override_traceability_reason` escape lets the user authorize a skip with a quoted rationale. | Always on for `/implement`; `lane: "quickfix"` opts out of the traceability prerequisite |
| Remote-quality substance (#1075) | `gc_watch_required_statuses` writes `remote_gates_green` only after re-verifying required remote statuses and the full configured provider result: quality-gate status, issue severities, ratings, hotspots, coverage, and duplication. SonarCloud/CodeQL/Codecov are provider adapters behind `remote_status`, not workflow phases. A green PR checkmark is not sufficient. | `.ground-control.yaml` → `remote_quality.tier` (`platform_minimum` or `zero_overall_issues`) plus optional coverage/duplication thresholds |
| Per-step and gate telemetry | `gc_log_step_telemetry` writes one JSONL line per routed step to `.gc/telemetry/<issue>-<sanitized-branch>.jsonl` (gitignored, repo-relative, containment-validated). `gc_gate_telemetry_summary` separately reports gate fire rate, outcomes, overrides/false-positive signals, escapes, and duration; `gc_capture_process_lessons` derives close-out lessons from that telemetry. Operational measurement only—never workflow state. | `.ground-control.yaml` → `telemetry.enabled` (default `false`) |

Each new tool is Temporal-shaped (deterministic, structured-input/output, no LLM call) so GC-O009 inherits them as activities when the Temporal workflow lands.

## Review Pipeline

One mandatory pre-implementation architecture pass, then a single pre-push codex review pass (Step 6.5), then test-quality review before the user sees the PR. The post-push codex review (former Step 12) was removed by issue #804—the canonical codex pass is the pre-push one, which catches everything codex would normally flag while collapsing the asymmetric "post-push finding → guaranteed CI/remote-quality roundtrip" cost. Merge-commit drift relative to base is the responsibility of CI (compile/tests/integration) and provider-neutral remote-quality gates, not a separate codex pass.

| Stage | What it catches | How it runs |
|-------|-----------------|-------------|
| Codex architecture preflight | Cross-cutting concerns, reuse opportunities, abstraction/concept confusion, need for ADR/design guidance before coding | `gc_codex_architecture_preflight` |
| Remote-quality providers | Quality-gate status, issue severities for new and overall code, reliability/security/maintainability ratings, reviewed security hotspots, coverage, and duplication | `gc_watch_required_statuses` with provider adapters such as SonarCloud behind `remote_status`; server-side API fetch, not a PR green-checkmark claim |
| Trivy (advisory) | Container image vulnerabilities, Dockerfile/IaC misconfigurations, in-image secrets | CI job; SARIF artifact `trivy-sarif` on the workflow run page; non-blocking |
| OSV-scanner (advisory) | CVEs in Java/Gradle dependencies (read from `backend/gradle.lockfile`) | CI job; SARIF artifact `osv-scanner-sarif` on the workflow run page; non-blocking |
| Codex review (pre-push, Step 6.5) | Correctness, security, and architecture lenses against stated requirements, contracts, plans, and binding ADRs. Codex returns verdict envelopes; the MCP server posts a verbatim findings record to the resolved issue thread from the host side; the coding agent dispatches only on the dispatcher-computed `next_action`. There is no PR yet at Step 6.5, so no inline PR comments are written by the SKILL—inline anchored comments only happen if a direct caller invokes `gc_codex_review` post-push (with a `pr_number`), which the SKILL no longer drives (issue #804). | `gc_codex_review` (`uncommitted=true`); MCP posts the issue-thread findings record |
| `gc_test_quality_review` (Step 6.6) | Test-strength lens: assertion-free tests, mock-only assertions, integration-as-unit, tests that can't detect regressions, and manifest-provided mutation / diff-coverage gate evidence | `gc_test_quality_review` MCP tool (shells out to `claude --print --model claude-sonnet-4-6` by default; full mechanism in `architecture/notes/test-quality-review-engine.md`) |

**Async execution (issue #937).** The codex review, codex architecture preflight, and test-quality review tools each spawn a child process (`codex exec` / `claude --print`) that legitimately runs for several minutes. Run synchronously, a single MCP tool-call blocked past the MCP client's per-call timeout; the client abandoned the call and the orphaned child left the workflow with no result handle (issue #893). All five tools (`gc_codex_architecture_preflight`, `gc_codex_review`, `gc_codex_review_cycle`, `gc_test_quality_review`, `gc_test_quality_review_cycle`) now take an opt-in `async` flag (default `false`; synchronous behavior unchanged for direct callers). With `async: true` the tool starts a background job and returns `{ok, status: "running", job_id}` immediately; the new `gc_codex_job` tool polls for the result envelope (`status: "done"` carries the full review result under `result`) or cancels a stuck job (cancel aborts an `AbortController` whose signal kills the child, so nothing is orphaned). The `/implement` step files (2.5 / 6.5 / 6.6) drive the start-then-poll loop. Client-side, `.claude/settings.json` sets `MCP_TOOL_TIMEOUT` / `MCP_TIMEOUT` so long-running MCP tools (including CI and remote-quality watchers) have headroom. Full design in ADR-036 (amendments).

All preflight/review stages operate under the same rule: **fix everything, defer nothing.** Review-loop cap (ADR-031): codex (Step 6.5) and test-quality (Step 6.6) are configurable per repo via `.ground-control.yaml::workflow.codex_review.pre_push_cap` and `workflow.test_quality_review.pre_push_cap` (configured bounds `[1, 10]`, effective minimum cap 2). Per-finding `gc_codex_verify_finding` cap stays at 2. If a cycle past the effective cap is needed, the dispatcher emits a structured decision aid and `override_cap=true` + `override_reason=<authorization quote>` is required per cycle; otherwise the skill stops at the terminal escalation.

"Defer nothing" is mechanically enforced (issue #830, ADR-029 § "`defer` is not a valid disposition"): the `.claude/hooks/block-defer-language.py` PreToolUse hook blocks `gh issue/pr {create,edit,comment,close}` calls carrying deferral-disposition language ("deferred to a follow-up PR," "addressed in a subsequent PR," "TBD later" in a closing comment, …), and `bin/policy` flags the same language in the PR body at completion gate. Filing a tracking issue does not convert a deferral into a valid disposition; the only valid ones are `fix`, `wontfix` (with explicit user authorization), or `not-applicable` (with rationale). Codex review additionally classifies each finding `one-off` or `class`; a `class` finding must be fixed at the **category** level (a structural gate / shared helper / parameterization; one point of repair applied to every instance), not whack-a-mole'd to the reviewer-named site.

## Guardrails

### Deny Rules (`~/.claude/settings.json`)
- `Bash(gh pr merge*)`—Claude cannot merge PRs
- `Bash(gh api */merge*)`—Claude cannot merge via API
- `Bash(git merge *)`—Claude cannot merge branches

### Attribution (`~/.claude/settings.json`)
```json
"attribution": { "commit": "", "pr": "" }
```
No Co-Authored-By, no "Generated with Claude Code," no AI attribution anywhere.

### Workflow Hooks (source of truth: `.claude/hooks/`)

The three user-level workflow hooks listed below are **checked into this repo** under `.claude/hooks/` and installed as **real file copies** at `~/.claude/hooks/<name>` by `scripts/bootstrap-claude-workflow.sh` (see **Tooling** below). Unlike skills (which are symlinked so edits in the repo take effect on the next session), hooks are copied because the harness execs them on every Bash tool call in every Claude Code session on the host. If the runtime path were a symlink into this repo's working tree, any `git checkout` in this repo would silently break hooks for every concurrent Claude window on the machine. Real copies decouple runtime from worktree state.

After editing a hook file under `.claude/hooks/` in the repo, re-run `scripts/bootstrap-claude-workflow.sh` (no arguments, idempotent) to copy the new version into `~/.claude/hooks/`. The `~/.claude/settings.json` hook registrations point at the stable `~/.claude/hooks/<name>` path and work regardless of what this repo is checked out to.

**Drift recovery.** The user-level copy can drift from the repo over time (a different repo's older bootstrap ran last, the host got reset and re-bootstrapped from a stale checkout, an agent edited the user-level file directly). To detect drift, run `scripts/bootstrap-claude-workflow.sh --dry-run`; the script reports any allowlisted hook whose user-level copy differs from the repo. To repair, run `scripts/bootstrap-claude-workflow.sh --force`; that path explicitly overwrites the user-level copy with the repo version (the script otherwise refuses to clobber, since drifted user-level content may be intentional). The repo is the source of truth, so resyncing in this direction is safe by construction. The hook contract is pinned by `tools/tests/test_git_merge_guard.py`, run by `make test` and pre-commit; if the repo hook ever regresses below the test contract the test suite catches it before the resync.

One user-level hook is deliberately NOT in the repo: `~/.claude/hooks/block-break-system-packages.sh`. It's a generic pip/apt safety gate unrelated to the Ground-Control workflow, so it stays host-local and `bootstrap-claude-workflow.sh` leaves it alone.

#### Stop Hook—`verify-implementation.sh`
Blocks Claude from completing, but **only when `/implement` was invoked in the current session**. Scoped by process ID (`$PPID`) so concurrent Claude windows on the same branch don't interfere.

Universal checks (all repos):
- Changelog fragment missing (when source files changed)—the hook requires a valid fragment under `changelog.d/<issue>.<type>.md` (or `+<slug>.<type>.md`), type ∈ `security`/`added`/`changed`/`deprecated`/`removed`/`fixed`. CI-only diffs (only `.github/workflows/`) and docs-only diffs (only docs/architecture/skills/metadata) are skipped. Refactors under application source still file a fragment (no "pure refactor" carve-out—enforcement is path-based). Direct `CHANGELOG.md` edits do NOT satisfy a source-changing diff. The source-path predicate and fragment vocabulary mirror `tools/policy/checks.py`—the `hook-matches-policy-vocabulary` and `hook-gates-on-application-source-predicate` policy tests keep them in sync.

Project-specific checks (`.claude/hooks/verify-extra.sh`, sourced if present):
- shared repo-native policy script (`bin/policy`) over the changed-file set

The hook no longer enforces `/review` and `/security-review`—those were removed from the `/implement` skill in favor of `gc_codex_review` + `gc_test_quality_review`. The `/implement` skill itself is the enforcement point for review coverage; the hook only guards the changelog signal + repo policy.

#### Skill Call Logging—`log-skill-call.sh`
PostToolUse hook on `Skill`—writes JSONL to `/tmp/claude-skill-log/<PID>.jsonl` (per-session, not per-branch). The Stop hook previously read this log to verify `/review` and `/security-review` were actually invoked; it's still wired up for forward compat in case we reintroduce skill-based checks. Stale logs (>24 h) are auto-pruned.

#### Git Merge Guard—`git-merge-guard.py`
PreToolUse hook on `Bash`. The user owns every actual merge. Blocked unconditionally: `git merge`, `gh pr merge`, `git reset --hard`, and a plain `git push --force` / `git push -f`. A `git push --force-with-lease` to a *feature* branch is allowed (that's the rebase-feature-branch-onto-base-then-update-the-PR flow), but a force-push of any kind to a ref named `main` or `dev` is blocked.

### Repo-Native Policy Layer

- `architecture/policies/adr-policy.json` defines machine-readable ADR guardrails
- `python3 bin/policy` enforces ADR/workflow, controller/MCP/docs, migration, and PR-body policy
- `make policy` is the common path for Claude, Codex, pre-commit, and CI
- `make sync-ground-control-policy` and `make policy-live` keep Ground Control quality gates and ADR metadata aligned when a live GC instance is available

## /integrate: Approved PR Integration Manager

The `/integrate` lane is the workflow path for preparing maintainer-approved pull requests against the latest base branch of a target repository. It is a lane for maintainers and release operators who need to rebase a queue of already-approved PRs to a clean state. By default the lane operates in **prepare-only** mode: it rebases, gates, verifies, and pushes, but it does not merge. Passing `--mode merge` enables the merge carve-out from the ADR-029 amendment (2026-05-26): the lane also executes `gh pr merge` for each PR it marks ready, per the configured `merge_strategy`. The `enqueue` mode remains reserved and refuses at runtime.

### When to use it

Use `/integrate` when you have a set of PRs in a target repository that carry an approval label (default `approved-for-integration`) and you want to rebase each one onto the current base branch, run the repo's completion gate and CI/remote-quality checks, and push the result. The lane does not replace human review: PRs must already carry the approval label before the lane touches them. It replaces the manual rebase-and-push step that a maintainer would otherwise do for each PR in turn.

Do not use `/integrate` to batch-merge PRs autonomously. That boundary is explicit: the lane prepares PRs for merge; the human merges.

### Invocation

```
/integrate [--repo <owner/repo>] [--base <branch>] [--label <label>] [--mode prepare|merge] [--dry-run]
```

All flags are optional. When `--repo` is omitted the lane reads `github_repo` from the target repo's `.ground-control.yaml`. `--base` overrides `workflow.base_branch`. `--label` overrides `workflow.integration_manager.approval_label`. `--mode` selects the execution mode (default `prepare`). `--dry-run` discovers and orders the queue without acquiring a lock or modifying any branch.

You can also call the underlying MCP tool directly:

```
gc_integration_manager action=status  repo_path=<path>
gc_integration_manager action=prepare repo_path=<path>
gc_integration_manager action=prepare repo_path=<path> mode=merge
gc_integration_manager action=release repo_path=<path>
```

### Configuration

The lane reads configuration from the `workflow.integration_manager` block in the target repo's `.ground-control.yaml`. All keys are optional; the defaults shown below apply when the block is absent.

```yaml
workflow:
  integration_manager:
    approval_label: approved-for-integration   # label that marks a PR ready
    ordering: pr_number_asc                    # pr_number_asc | pr_number_desc | approved_at_asc
    max_queue_size: 20                         # integer, [1, 100]
    merge_strategy: merge                      # merge | squash | rebase (default: merge)
```

`merge_strategy` controls the `--merge`, `--squash`, or `--rebase` flag passed to `gh pr merge` when the lane runs in `mode=merge`. The key is only consulted in merge mode; it has no effect in prepare-only mode.

The parser (`normalizeIntegrationManagerConfig` in `mcp/ground-control/lib.js`) enforces the same strict-unknown-key rule as the rest of the workflow config. Unrecognized keys are rejected with a validation error, not silently ignored.

### Maintainer approval signal

A PR is eligible for the queue when it carries the configured approval label. No review state or comment is required by default. The label is the sole eligibility signal, so maintainers can approve by label from the GitHub UI, the CLI, or an automation rule without any integration-manager-specific ceremony.

Remove the label to pull a PR from the queue. The lane re-discovers the queue at each run, so a label change between runs takes effect on the next invocation.

### What the lane does to each approved PR

For each PR in queue order:

1. **Acquire lock.** A repo-level lock under `.gc/integration-lock.json` prevents concurrent runs from preparing the same PR twice. If a lock is already held by another run, the lane halts and reports the lock holder.
2. **Create isolated worktree.** Each PR is processed in a temporary worktree so the main working tree is not disturbed.
3. **Rebase onto base branch.** The PR branch is rebased onto the latest `origin/<base>`. Rebase conflicts halt that PR with a `blocked` failure mode; the queue continues to the next PR (see Failure modes below).
4. **Run completion gate.** The repo's configured `workflow.completion_command` (typically `make check`) runs in the worktree. Failure halts the queue.
5. **Watch CI.** After pushing, the lane polls the GitHub Actions run for the PR and waits for a terminal conclusion. CI failure halts the queue.
6. **Watch remote quality.** If the repo configures a remote-quality provider, the lane waits for provider analysis and inspects the quality gate. A failed quality gate halts the queue.
7. **Force-with-lease push.** When all gates pass, the lane pushes the rebased branch to `origin` with `--force-with-lease`. This updates the PR's head SHA without disturbing any concurrent push to a different branch.
8. **Release lock.** The lock is released after each PR regardless of outcome, so the next PR can be processed.

### Failure modes

Three failure severities apply:

- **`blocked`**: the current PR cannot be prepared (rebase conflict, completion-gate failure, CI failure, remote-quality failure). The queue continues to the next PR. The blocked PR is recorded in the run ledger at `<repo>/.gc/integration-runs/<run-id>/halt.json`.
- **`queue_wide_halt`**: a condition prevents any further processing (lock conflict from a concurrent run, invalid configuration, missing base branch). The queue stops. No further PRs are processed in this invocation.
- **`consultation_halt`**: the lane encounters a condition that requires maintainer judgment before proceeding. The queue stops and the lane reports the condition through the invoking interface. The consultation criteria from GC-O011 clause (h) are: the PR diff touches a file that another queued PR also touches (ordering-sensitive overlap); the rebase produces a commit that is structurally different from the original PR's commits in a way the lane cannot resolve deterministically; the CI run produces a failure log that names a file modified by a different PR in the queue; or the quality gate failure names a metric that was passing before the rebase and failing after. In any of these cases the lane must stop and consult the maintainer rather than proceeding with a potentially incorrect ordering or an attribution-ambiguous failure.

### What the lane does NOT do

- No automatic merging in default mode. With `--mode prepare` (default), the lane prepares PRs; a human merges them. Use `--mode merge` to enable the merge carve-out.
- No `enqueue` mode. Enqueue is reserved and refuses at runtime.
- No requirement status transitions. The lane does not touch Ground Control requirement states.
- No traceability reconciliation. The lane does not create or delete IMPLEMENTS/TESTS links against the PRs it prepares.
- No issue-thread comments. Consultation halts and status reports surface through the invoking interface (terminal output and the MCP tool's return envelope), not as comments on any GitHub issue thread.

### Operating tips

Check lock state and queue without modifying anything:

```
gc_integration_manager action=status repo_path=<path>
```

Release a stale lock (for example, from a run that was interrupted before cleanup):

```
gc_integration_manager action=release repo_path=<path>
```

Inspect the run ledger for the most recent halt:

```
cat <repo>/.gc/integration-runs/<run-id>/halt.json
```

Pass `--dry-run` to the `/integrate` skill to see what the queue would contain without acquiring a lock or touching any branch.

## Standalone Skills

Workflow skills live in **two** repo roots, each with its own installer. The two name sets are disjoint, so the two install paths can never resolve the same name to different definitions:

- **`skills/<name>/SKILL.md`**—agent-neutral skills shared by Claude Code *and* Codex (per ADR-027). `bin/install-skills.sh` installs each into `~/.claude/skills/<name>`, `~/.codex/skills/<name>`, and (legacy alias) `~/.codex/prompts/<name>.md`.
- **`.claude/skills/<name>/SKILL.md`**—Claude-Code-only skills. `scripts/bootstrap-claude-workflow.sh` symlinks each into `~/.claude/skills/<name>` (see **Tooling** below).

In both cases this repo is the source of truth: edit the `SKILL.md`, commit, and the change takes effect for the next Claude Code (or Codex) session on a host whose install paths are symlinks into the repo. Re-run the relevant installer after a host reset.

| Skill | Repo root | Purpose |
|-------|-----------|---------|
| `/implement <issue-number \| uid>` | `skills/` | Full end-to-end: plan through PR-ready |
| `/integrate` | `skills/` | Approved-PR integration manager: rebase, gate, verify, and push a queue of approved PRs (prepare-only; see GC-O011) |
| `gc_test_quality_review` | `mcp/ground-control/` | Test-quality review—MCP tool (per #884 v2; replaces the prior `/review-tests` Skill) |
| `/ship` | `.claude/skills/` | Ship an already-committed branch (CI, reviews, fix, report) |
| `/stage` | `.claude/skills/` | Stage files + pre-commit loop |
| `/gh-workflow-monitor` | `.claude/skills/` | Monitor GitHub Actions workflow runs |
| `/repo-setup` | `.claude/skills/` | Set up branch protection + pre-commit + SonarQube wiring on a fresh repo |
| `/wave-issue-coverage` | `.claude/skills/` | Back-fill GitHub issues for a wave's DRAFT requirements |

## Tooling

Repo-local scripts live under `scripts/` (bash) and `bin/` (Python). The ones you're most likely to run by hand:

| Command | Purpose |
|---------|---------|
| `scripts/bootstrap-claude-workflow.sh` | Wire the Claude-Code-only surfaces from `~/.claude/`: the `.claude/skills/<name>/` skills (symlinked—edit takes effect live) and the `WORKFLOW_HOOKS` allowlist under `.claude/hooks/` (**copied** as real files so runtime does not depend on which branch this repo is checked out to). Idempotent; safe to re-run. Pass `--dry-run` to preview, `--force` to clobber non-matching host content. The hook allowlist is explicit, so generic host-local hooks (for example, `block-break-system-packages.sh`) are left alone. Re-run after editing a hook file in the repo to push the new version into `~/.claude/hooks/`. Does **not** touch the `skills/<name>/` agent-neutral skills—that's `bin/install-skills.sh`'s job. |
| `bin/install-skills.sh` | Install the agent-neutral `skills/<name>/` skills (currently just `/implement`; the prior `/review-tests` was removed in #884 v2 in favor of the `gc_test_quality_review` MCP tool) into `~/.claude/skills/<name>`, `~/.codex/skills/<name>`, and `~/.codex/prompts/<name>.md` (legacy alias). Symlinks by default (`--copy` to hard-copy, `--dry-run` to preview, `--no-codex` to skip the Codex targets, `--force` to overwrite divergent host content). Idempotent; refuses to clobber unmanaged host targets without `--force`. |
| `scripts/pack-sync.sh` | Trigger the `pack-registry-sync` GitHub workflow against this repo. |
| `bin/policy` | Run the repo-native policy guardrails (ADR sync, controller/MCP/docs parity, migration policy, PR-body checks). Invoked by `make policy`, pre-commit, and CI. |
| `bin/adr-guard` | ADR-specific policy checks run standalone. |
| `bin/scaffold-controller`, `bin/scaffold-audited-entity`, `bin/scaffold-l2-state-machine` | Generators that start new code from a compliant shape. Wrapped by `make scaffold-*`. |
| `bin/check-pr-body` | Validate a PR body against the required template. |

### Bootstrapping a fresh host

After cloning this repo onto a new host (or after any `rm -rf ~/.claude/skills/` or `rm -rf ~/.claude/hooks/` reset), run **both** installers:

```
scripts/bootstrap-claude-workflow.sh   # .claude/skills/* skills + the WORKFLOW_HOOKS allowlist under .claude/hooks/
bin/install-skills.sh                  # skills/* (agent-neutral) into ~/.claude/skills, ~/.codex/skills, ~/.codex/prompts
```

`scripts/bootstrap-claude-workflow.sh` walks:
- `.claude/skills/*/`—every skill directory gets a matching `~/.claude/skills/<name>` **symlink**. Editing a skill in the repo takes effect immediately in the next session.
- `.claude/hooks/`—only the hooks listed in the script's `WORKFLOW_HOOKS` allowlist (`git-merge-guard.py`, `block-defer-language.py`, `log-skill-call.sh`, `verify-implementation.sh`) are installed as **real file copies** at `~/.claude/hooks/<name>`. Editing a hook in the repo requires re-running this script to push the new version out. Repo-scoped hooks (`protect_files.sh`, `verify-extra.sh`) stay where they are because they're wired via `$CLAUDE_PROJECT_DIR` in `.claude/settings.json`, not via `~/.claude/`.

`bin/install-skills.sh` symlinks each `skills/<name>/` directory (currently `/implement`; the prior `/review-tests` was removed in #884 v2—see `architecture/notes/test-quality-review-engine.md`) into `~/.claude/skills/<name>`, `~/.codex/skills/<name>`, and `~/.codex/prompts/<name>.md`. Pass `--no-codex` if Codex isn't on the host.

If a pre-existing host file or directory has local changes that are NOT in the repo, the script refuses to clobber it and exits non-zero—re-run with `--force` only after you've confirmed the repo copy is the version you want. Already-correct entries are left alone.

## Test tooling beyond unit tests (#931)

The `make test` target runs the unit-test suite; the project also ships three
complementary test-quality signals:

| Signal | Purpose | How to run |
|--------|---------|-----------|
| **Mutation testing (Pitest)** | Directly measures whether the unit tests detect breakage. A high mutation-kill score is a stronger signal than line coverage. | `make test-quality` |
| **Property-based testing (jqwik)** | Already wired on five domain surfaces—cycle detection, finding-status state machine, impact analysis, audit-status state machine, requirement-status transitions. Property tests find edge cases TDD misses by construction. | `make test` (runs alongside the unit suite) |
| **Dependency / SBOM scanning (OSV + Trivy)** | OSV-scanner runs against `backend/gradle.lockfile` in CI. Findings are advisory, **except**: any new CRITICAL CVE fails the job (added in #931). Trivy scans the deploy image + IaC, advisory-only. | `.github/workflows/ci.yml` (`osv-scanner` job) |

Pitest's initial thresholds are intentionally loose (60% mutation, 0% coverage)
so the very first PR doesn't fail before there is calibration data. After the
first ~5 PRs of mutation-score evidence, tighten via `pitest { mutationThreshold = ... }`
in `backend/build.gradle.kts`.

## Key Lessons (from GC-J001 first run)

- **Write `@WebMvcTest` controller tests**, not just integration tests. SonarCloud CI doesn't run Testcontainers.
- **Update `MigrationSmokeTest` and `RequirementsE2EIntegrationTest`** version lists when adding migrations.
- **Add `@NotAudited` to `@ManyToOne` references** to non-audited entities when using `@Audited`.
- **Add `_audit` table migration** when adding `@Audited` entities.
- **Default durable mutable entities to `BaseEntity`**. Only keep standalone lifecycle fields for intentionally append-only, snapshot, cache, or import/audit records.
- **Use the scaffold commands** (`make scaffold-controller`, `make scaffold-audited-entity`, `make scaffold-l2-state-machine`) to start from a compliant shape.
