# Ground Control MCP Server

MCP server wrapping the Ground Control REST API plus a small set of
Ground-Control-specific workflow tools, including Codex-backed architecture
preflight and review helpers.

## Setup

Add to your Claude Code MCP config (`.claude/settings.json` or project
`.mcp.json`):

```json
{
  "mcpServers": {
    "ground-control": {
      "command": "node",
      "args": ["/path/to/Ground-Control/mcp/ground-control/index.js"],
      "env": {
        "GC_BASE_URL": "http://red-dragon:8000"
      }
    }
  }
}
```

Requires a reachable Ground Control instance. For local backend development:

```sh
make up && make dev
```

`GC_BASE_URL` is required. The repo does not provide a committed default host.
Set it in your user-local MCP config or shell environment.

### Bearer token (ADR-026)

Production deployments enforce `groundcontrol.security.enabled=true` and
require `Authorization: Bearer <token>` on every `/api/v1/**` call. The
MCP server reads the token from a `.env` file in the consumer repo's
root (the cwd it was launched from) at startup:

```sh
# In each repo where you start Claude Code / Codex against Ground Control:
cp .env.example .env       # if your repo has the template - Ground-Control does
chmod 600 .env
# Edit .env and set GROUND_CONTROL_API_TOKEN=<32-byte-hex token>
```

The token never appears in `.mcp.json` and is never exposed to the LLM.
A shell-exported `GROUND_CONTROL_API_TOKEN` still wins over the `.env`
value for one-off / CI callers that prefer the env-var-only flow. Make
sure `.env` is gitignored.

The legacy `GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN` is also resolved
from `.env`; it is preferred over `GROUND_CONTROL_API_TOKEN` for paths
requiring `ROLE_ADMIN` (`/api/v1/admin/**`, `/api/v1/embeddings/**`,
`/api/v1/analysis/sweep/**`, `/api/v1/pack-registry/**`).

Codex-backed workflow tools additionally require the Codex CLI to be installed
and available on `PATH`.

## Admin-tool opt-in (`GC_MCP_ADMIN`)

The `gc_admin` and `gc_pack` consolidated tools wrap `/api/v1/admin/**`,
`/api/v1/embeddings/**`, `/api/v1/analysis/sweep/**`, and
`/api/v1/pack-registry/**` operations that require `ROLE_ADMIN` at the
backend (per ADR-026). To avoid surfacing those write/mutating actions to
a default MCP session that happens to have an admin bearer token in its
environment, both tools are registered **only when `GC_MCP_ADMIN=1`** (or
`true` / `yes`). Without the flag, neither tool appears in the catalog. A
session that needs admin operations sets the env var explicitly:

```jsonc
{
  "mcpServers": {
    "ground-control-admin": {
      "command": "node",
      "args": ["/path/to/Ground-Control/mcp/ground-control/index.js"],
      "env": {
        "GC_BASE_URL": "http://red-dragon:8000",
        "GC_MCP_ADMIN": "1"
      }
    }
  }
}
```

Backend `ROLE_ADMIN` still enforces authorization; this flag controls only
which named MCP tools are advertised to the LLM.

## Tool surface (ADR-035)

The MCP server registers **~30 named tools** plus the read-only `gc_query`
escape hatch, down from 215 in earlier versions. The surface was consolidated
under ADR-035 by combining `gc_create_X` / `gc_get_X` / `gc_list_X` /
`gc_update_X` / `gc_delete_X` per-entity tools into a single
`gc_<entity>` tool with an `action` discriminator, and by moving pure-read
GETs (history, timeline, exports, list-by-X) onto `gc_query`.

**Workflow primitives** (called by name from the `/implement`, `/ship`, and
`/integrate` skills' SKILL.md prose, kept unchanged):

| Tool | Purpose |
|---|---|
| `gc_get_repo_ground_control_context` | Read repo's `.ground-control.yaml` |
| `gc_dashboard_stats` | Aggregate project health snapshot |
| `gc_get_requirement` | Get requirement by UID |
| `gc_get_traceability` | Get all traceability links for a requirement |
| `gc_get_traceability_by_artifact` | Reverse lookup: artifact → requirements; accepts optional `project` to scope by project (required for correct results with `GITHUB_ISSUE` artifacts) |
| `gc_create_traceability_link` | Link an artifact to a requirement |
| `gc_delete_traceability_link` | Delete a traceability link |
| `gc_transition_status` | Transition a requirement's status |
| `gc_bulk_transition_status` | Transition many requirements at once |
| `gc_create_github_issue` | Create a GitHub issue from a requirement |
| `gc_codex_architecture_preflight` | Codex preflight before `/implement` |
| `gc_post_implementation_plan` | Post plan to issue thread; requires the `preflight` marker |
| `gc_codex_review` | Run Codex review with cycle caps |
| `gc_codex_verify_finding` | Verify a specific finding resolved |
| `gc_query` | Read-only ad-hoc `/api/v1/*` GET (see below) |
| `gc_integration_manager` | Approved-PR integration manager (see below) |

**Consolidated entity tools** (one per entity, action-discriminated):

| Tool | Actions |
|---|---|
| `gc_requirement` | list, create (`uid` or `uid_prefix`, exactly one required), update, delete, archive, clone |
| `gc_relation` | create, get, delete |
| `gc_adr` | create, update, delete, transition, requirements |
| `gc_document` | create, update, delete, grammar_set, grammar_delete, reading_order |
| `gc_section` | create, update, delete, tree, content_add, content_update, content_delete |
| `gc_asset` | create, update, delete, archive, relation_create, relation_update, relation_delete, link_*, external_id_*, subtype_schema_*, detect_cycles, impact_analysis, extract_subgraph |
| `gc_observation` | create, update, delete, latest |
| `gc_risk_scenario` | create, update, delete, transition, requirements, link_* |
| `gc_threat_model` | create, update, delete, transition, link_* |
| `gc_control` | create, update, delete, transition, link_* |
| `gc_risk_governance` | `{entity, action}` over verification_result |
| `gc_analyze` | cycles, orphans, coverage_gaps, impact, cross_wave, consistency, completeness, status_drift, similarity, work_order |
| `gc_graph` | ancestors, descendants, paths, find_paths, subgraph, visualization, traverse |
| `gc_baseline` | create, delete, snapshot, compare |
| `gc_quality_gate` | create, update, delete, evaluate |
| `gc_admin` | imports, sync, embeddings, materialize_graph, project, sweep, exports |
| `gc_pack` | `{subsystem, action}` over plugin, registry, trust_policy, install |

Reads (list, get-by-id, history, diff, timeline, exports) for any entity go
through `gc_query` against the appropriate `/api/v1/*` path.

## Read-only ad-hoc queries via `gc_query`

`gc_query` is a tightly bounded GET-only escape hatch into `/api/v1/**` for
hypothesis-checking that the curated tools don't pre-bake. It is registered
under the `workflow` catalog, so it is always available.

- **Method:** GET only (no `method` parameter exists).
- **Path:** must be a relative `/api/v1/...` path under one of the
  allowlisted prefixes. Absolute URLs, protocol-relative URLs, `..` segments,
  embedded `?` / `#`, and any path outside `/api/v1/` are rejected before any
  network call.
- **Allowlist (canonical source: `GC_QUERY_PATH_ALLOWLIST` in
  `mcp/ground-control/gc-query.js`):**
  `/api/v1/adrs`, `/api/v1/analysis`, `/api/v1/assets`, `/api/v1/audit`,
  `/api/v1/audits`, `/api/v1/baselines`,
  `/api/v1/control-tests`, `/api/v1/controls`, `/api/v1/dashboard`,
  `/api/v1/documents`, `/api/v1/evidence-artifacts`, `/api/v1/findings`, `/api/v1/graph`,
  `/api/v1/mcp-tool-usage`,
  `/api/v1/observations`, `/api/v1/projects`, `/api/v1/quality-gates`,
  `/api/v1/relations`, `/api/v1/requirements`, `/api/v1/research-runs`,
  `/api/v1/risk-scenarios`, `/api/v1/sections`, `/api/v1/test-cases`,
  `/api/v1/test-plans`, `/api/v1/test-runs`, `/api/v1/test-suites`, `/api/v1/threat-models`, `/api/v1/timeline`,
  `/api/v1/traceability`,
  `/api/v1/verification-results`,
  `/api/v1/workflow-runs`, `/api/v1/workflow-runs/aggregate`. A drift-catch test
  (`gc-query.test.js`) compares this list against the implementation
  constant on every test run, so the README is the documentation surface
  but the constant in `gc-query.js` is the truth.
- **Denylist:** `/api/v1/admin/**`, `/api/v1/embeddings/**`,
  `/api/v1/analysis/sweep/**`, `/api/v1/pack-registry/**` are rejected even
  though they live under `/api/v1/`. The denylist takes precedence over the
  allowlist when prefixes overlap (for example, `/api/v1/analysis` is allowed but
  `/api/v1/analysis/sweep` is denied).
- **Headers:** none accepted. `X-Actor: mcp-server` and the bearer token
  resolved from `GROUND_CONTROL_API_TOKEN` (or the legacy admin token, when
  appropriate per ADR-026's path matrix) are added automatically.
- **Params:** flat object, values must be `string | number | boolean | null`.
  `undefined` and `null` are dropped before URL construction.
- **Cost cap:** 30 s timeout via `AbortController`; 1 MiB response-body cap
  with a clear truncation marker. Larger results need the catalog tool that
  paginates the resource (in `analysis` or `requirements`).

Adding a new read endpoint to the agent's reach requires updating the
`GC_QUERY_PATH_ALLOWLIST` constant in `mcp/ground-control/gc-query.js`. The
default-deny posture is intentional.

## Workflow

Operations have a natural ordering. Requirements must exist before they can
be related, linked, or analyzed.

1. **Create requirements**: `gc_create_requirement` with uid, title, statement
2. **Create relations**: `gc_create_relation` between two existing requirements
3. **Add traceability links**: `gc_create_traceability_link` to connect code, tests, issues, ADRs
4. **Run analysis**: `gc_analyze_cycles`, `gc_analyze_orphans`, `gc_analyze_coverage_gaps`, `gc_analyze_impact`, `gc_analyze_cross_wave`, `gc_analyze_consistency`, `gc_analyze_completeness`, `gc_analyze_status_drift`
5. **Embed requirements**: `gc_embed_project` to generate vector embeddings, `gc_analyze_similarity` to find near-duplicates
6. **Transition status**: `gc_transition_status` moves requirements forward: DRAFT → ACTIVE → DEPRECATED → ARCHIVED
7. **Bulk operations**: `gc_import_strictdoc` for .sdoc files, `gc_import_reqif` for .reqif files, `gc_sync_github` for issue sync
8. **Manage baselines**: `gc_create_baseline`, `gc_compare_baselines` for release management

## Tool Reference

| Tool | Parameters | Purpose |
|------|-----------|---------|
| `gc_create_requirement` | `uid` or `uid_prefix` (exactly one required), `title` (required), `statement` (required), `rationale`, `requirement_type`, `priority`, `wave` | Create a requirement. Provide `uid` for an explicit UID or `uid_prefix` to let the server allocate the next available `{PREFIX}-{N}`. Status defaults to DRAFT |
| `gc_get_requirement` | `uid` (required) | Get requirement by UID |
| `gc_list_requirements` | `status`, `type`, `wave`, `search`, `page`, `size`, `sort` | List requirements with optional filters. Paginated, sortable |
| `gc_update_requirement` | `id` (required), `uid`, `title`, `statement`, `rationale`, `requirement_type`, `priority`, `wave` | Update fields on an existing requirement. Pass only changed fields |
| `gc_transition_status` | `id` (required), `status` (required) | Transition requirement status. Forward-only |
| `gc_archive_requirement` | `id` (required) | Shortcut to transition to ARCHIVED |
| `gc_create_relation` | `source_id` (required), `target_id` (required), `relation_type` (required) | Create a directed relation between two requirements |
| `gc_get_relations` | `id` (required) | Get all relations (incoming and outgoing) for a requirement |
| `gc_get_traceability` | `id` (required) | Get all traceability links for a requirement |
| `gc_create_traceability_link` | `requirement_id` (required), `artifact_type` (required), `artifact_identifier` (required), `link_type` (required), `artifact_url`, `artifact_title` | Link an artifact to a requirement |
| `gc_analyze_cycles` | _(none)_ | Detect dependency cycles in the requirements graph |
| `gc_analyze_orphans` | _(none)_ | Find requirements with no relations |
| `gc_analyze_coverage_gaps` | `link_type` (required) | Find requirements missing a specific link type |
| `gc_analyze_impact` | `id` (required) | Transitive impact analysis from a given requirement |
| `gc_analyze_cross_wave` | _(none)_ | Find cross-wave dependency violations |
| `gc_analyze_consistency` | `project` (optional) | Detect consistency violations (active conflicts, active supersedes) |
| `gc_analyze_completeness` | `project` (optional) | Analyze completeness: status distribution and missing fields |
| `gc_analyze_status_drift` | `project` (optional), `minimum_confidence` (optional: HIGH/MEDIUM/LOW, default MEDIUM) | Find DRAFT requirements with independent evidence of implementation (IMPLEMENTS links, accepted ADRs, linked issues/PRs, code-artifact links, all from the project's own traceability links). Read-only; reports confidence and evidence artifacts |
| `gc_dashboard_stats` | `project` (optional) | Aggregate project health: counts by status/wave, coverage percentages, recent changes |
| `gc_get_work_order` | `project` (optional) | Topological work order with MoSCoW priority |
| `gc_dashboard_stats` | `project` (optional) | Aggregate project health: counts, coverage, recent changes |
| `gc_run_sweep` | `project` (optional) | Run full analysis sweep on one project |
| `gc_run_sweep_all` | _(none)_ | Run analysis sweep across all projects |
| `gc_import_strictdoc` | `file_path` (required), `project` (optional) | Import requirements from a .sdoc file. Idempotent |
| `gc_import_reqif` | `file_path` (required), `project` (optional) | Import requirements from a .reqif file. Idempotent |
| `gc_sync_github` | `owner` (required), `repo` (required) | Sync GitHub issues as traceability links |
| `gc_create_github_issue` | `uid` (required), `repo`, `labels`, `extra_body` | Create GitHub issue from requirement and auto-link |
| `gc_get_repo_ground_control_context` | `repo_path` (required) | Read and validate the repo's `.ground-control.yaml` context (project, workflow, sonarcloud, rules, knowledge). Returns inlined plan_rules content and resolved knowledge paths when those sections are configured. Tolerates and ignores a legacy `grc.*` block (ADR-089)—never returned |
| `gc_resolve_workflow_route` | `repo_path` (required), `stage` (required), `tier` (optional) | Resolve advisory provider, canonical model id, and capability tier metadata for a workflow stage |
| `gc_implement_mechanical` | `action`, `repo_path`, `issue_number`; action-specific branch, synchronization, PR, or completion fields | Execute contiguous deterministic `/implement` bands (`bootstrap`, `verify`, `publish`, `monitor`, `readiness`, `finalize`) in one call; return `agent_required=true` only for bounded actionable failures |
| `gc_synchronize_implement_branch` | `repo_path`, `issue_number`, `branch_name`, `action`; completion also requires `record_id`, `pre_sync_sha`, `fetched_base_sha`, `outcome` | Fetch and merge the configured integration branch in the invocation checkout; mechanically run completion and policy on the exact merged tree; publish it; post an idempotent durable attestation |
| `gc_create_synchronized_implement_pr` | `repo_path`, `issue_number`, `branch_name`, `record_id`, `title`, `body` | Re-fetch and verify the trusted synchronization attestation, verified tree, local/remote heads, and repository-scoped existing PR identity before creating an `/implement` PR |
| `gc_codex_architecture_preflight` | `requirement_uid` (required), `repo_path` (required), `project`, `issue_number`, `repo` | Run Codex architecture preflight, update ADR/design guidance when needed, and return guardrails plus changed files |
| `gc_codex_review` | `repo_path` (required), `base_branch`, `uncommitted` | Run Codex review with an exhaustive no-triage production-quality prompt |
| `gc_embed_requirement` | `requirement_id` (required) | Generate embedding for a requirement's text |
| `gc_get_embedding_status` | `requirement_id` (required) | Check embedding status (stale, model mismatch) |
| `gc_embed_project` | `project` (optional), `force` (optional) | Batch-embed all requirements in a project |
| `gc_analyze_similarity` | `project` (optional), `threshold` (optional) | Find semantically similar requirement pairs |
| `gc_get_graph_visualization` | `project` (optional) | Get all requirements and relations for visualization |
| `gc_materialize_graph` | _(none)_ | Materialize Apache AGE graph |
| `gc_get_ancestors` | `uid` (required), `depth` (optional) | Get ancestor UIDs via graph traversal |
| `gc_get_descendants` | `uid` (required), `depth` (optional) | Get descendant UIDs via graph traversal |
| `gc_find_paths` | `source` (required), `target` (required) | Find all paths between two requirements |
| `gc_extract_subgraph` | `roots` (required) | Extract subgraph from root UIDs |
| `gc_create_baseline` | `name` (required), `description`, `project` (optional) | Create point-in-time baseline |
| `gc_list_baselines` | `project` (optional) | List all baselines |
| `gc_get_baseline` | `id` (required) | Get baseline details |
| `gc_get_baseline_snapshot` | `id` (required) | Get requirement snapshot at baseline |
| `gc_compare_baselines` | `id` (required), `other_id` (required) | Compare two baselines |
| `gc_delete_baseline` | `id` (required) | Delete a baseline |
| `gc_clone_requirement` | `id` (required), `new_uid` (required), `copy_relations` (optional) | Clone a requirement |
| `gc_bulk_transition_status` | `ids` (required), `status` (required) | Bulk transition multiple requirements |
| `gc_delete_relation` | `requirement_id` (required), `relation_id` (required) | Delete a relation |
| `gc_delete_traceability_link` | `requirement_id` (required), `link_id` (required) | Delete a traceability link |
| `gc_get_requirement_history` | `id` (required) | Get requirement revision history |
| `gc_get_relation_history` | `requirement_id` (required), `relation_id` (required) | Get relation revision history |
| `gc_get_traceability_link_history` | `requirement_id` (required), `link_id` (required) | Get link revision history |
| `gc_get_timeline` | `id` (required), `change_category`, `actor`, `from`, `to`, `limit`, `offset` | Unified audit timeline for a requirement |
| `gc_get_project_timeline` | `project`, `change_category`, `actor`, `from`, `to`, `limit`, `offset` | Unified audit timeline across all requirements in a project |
| `gc_export_audit_timeline` | `project`, `change_category`, `actor`, `from`, `to`, `limit` | Export project audit timeline as CSV |
| `gc_export_requirements` | `project`, `format` | Export requirements as CSV, Excel (.xlsx), or PDF |
| `gc_export_sweep_report` | `project`, `format` | Run sweep analysis and export as CSV, Excel, or PDF |
| `gc_export_document` | `document_id`, `format` | Export document (sdoc, html, pdf, or reqif) |

## Enums

**Status:** `DRAFT`, `ACTIVE`, `DEPRECATED`, `ARCHIVED`

**Requirement type:** `FUNCTIONAL`, `NON_FUNCTIONAL`, `CONSTRAINT`, `INTERFACE`

**Priority (MoSCoW):** `MUST`, `SHOULD`, `COULD`, `WONT`

**Relation type:** `PARENT`, `DEPENDS_ON`, `CONFLICTS_WITH`, `REFINES`, `SUPERSEDES`, `RELATED`

**Artifact type:** `GITHUB_ISSUE`, `CODE_FILE`, `ADR`, `CONFIG`, `POLICY`, `TEST`, `SPEC`, `PROOF`, `DOCUMENTATION`

**Link type:** `IMPLEMENTS`, `TESTS`, `DOCUMENTS`, `CONSTRAINS`, `VERIFIES`

## Write-contract drift gate (ADR-034, #1106)

Each write tool's request-body field allowlist and enum mirror is checked against
the backend's generated OpenAPI contract by `make mcp-openapi-contract` (CI job
`mcp-contract`). The inventory lives in `openapi-contract.test.js`: one row per
tool/entity/action naming the exported field array (for example
`GC_AUDIT_CREATE_BODY_FIELDS`, `CONTROL_FIELDS.control.create`,
`GOVERNANCE_FIELDS.<entity>.<action>`, `LINK_CREATE_BODY_FIELDS`) and the OpenAPI
request schema it must agree with. When you add or change a write tool's fields,
update its exported array and add or adjust the matching inventory row, and the
gate fails the build on drift, naming the tool, field, and which side diverged.
Requirement: GC-O013.

## Status Transitions

Forward-only. No backward transitions.

```
DRAFT → ACTIVE → DEPRECATED → ARCHIVED
```

`gc_transition_status` enforces this. `gc_archive_requirement` is a shortcut
that transitions directly to ARCHIVED (must already be DEPRECATED).

## Error Handling

Errors return:

```json
{
  "error": {
    "code": "NOT_FOUND",
    "message": "Requirement not found",
    "detail": {}
  }
}
```

Common codes: `NOT_FOUND` (404), `CONFLICT` (409), `VALIDATION_ERROR` (422).

## IDs

`gc_create_requirement` and `gc_get_requirement` use `uid` (human-readable,
for example, `REQ-001`). All other tools use `id` (UUID, returned in create/list
responses).

For cross-repo workflow automation, define repo-local Ground Control context in a `.ground-control.yaml` file at the repo root. At minimum it must declare `schema_version: 1` and a `project` identifier; optional sections include `workflow`, `sonarcloud`, `rules`, `knowledge`, `routing`, `telemetry`, plus the workflow-packaging fields added in ADR-027: `docs.{adr_dir, architecture_overview, coding_standards, workflow_reference, knowledge_base}`, `example_paths.{source, test}`, `requirements.uid_examples`, and `cross_cutting_concerns.description`. A legacy `grc.*` block from a pre-ADR-089 config is tolerated and ignored—never validated, parsed, or returned. The canonical `skills/implement/SKILL.md` renders prose against these fields via `{cfg.X|default Y}` placeholders so one source of truth serves every Ground-Control-aware repo. The `gc_get_repo_ground_control_context` MCP tool reads and validates this file and returns inlined `plan_rules` content plus resolved `knowledge` paths and the workflow-packaging blocks when those sections are configured. `gc_resolve_workflow_route` reads the same config and resolves `routing.stages.<stage>` entries to advisory provider/model/tier metadata; it does not choose an executor or force delegation. See `docs/DEVELOPMENT_WORKFLOW.md` for the full accepted config shape, defaults, allowed routing values, and validation constraints. `buildSuggestedGroundControlYaml()` in `lib.js` is only the starter template.

## gc_integration_manager: Approved PR Integration Manager

`gc_integration_manager` prepares maintainer-approved pull requests against the latest base branch in a target repository. It is the MCP-layer substrate for the `/integrate` skill (GC-O011, issue #989). The tool is action-discriminated:

| Action | Description |
|--------|-------------|
| `prepare` | Discover the approval-labeled queue, acquire the repo-level lock, and for each PR in deterministic order: create an isolated worktree, rebase onto the base branch, run the completion gate, watch CI, watch SonarCloud (when configured), and push with `--force-with-lease`. |
| `status` | Report current lock state and queue contents without acquiring the lock or modifying any branch. Safe to run at any time. |
| `release` | Release a stale lock held by an interrupted run. Requires the caller to confirm the previous run is no longer active. |
| `enqueue` | **Reserved.** Refuses at runtime until ADR-029 is amended to add an automated merge touchpoint. |
| `merge` | **Reserved.** Refuses at runtime until ADR-029 is amended to add an automated merge touchpoint. |

The tool reads `workflow.integration_manager` from the target repo's `.ground-control.yaml` (`approval_label`, `ordering`, `max_queue_size`). It does not merge PRs, does not transition requirement statuses, and does not post to GitHub issue threads. Consultation halts and status reports are returned in the tool's response envelope and written to `<repo>/.gc/integration-runs/<run-id>/halt.json`. See `skills/integrate/SKILL.md` for the full lane specification and GC-O011 for the requirement.

## Codex review architecture (privileged side-effect boundary)

Per ADR-027 and issue #793, the codex-backed review tools follow a strict separation of concerns:

- **Codex is the planner / reviewer.** It runs in a `read-only` sandbox with no GitHub credentials and returns structured payloads only. It must never invoke `gh`, `git`, or `curl` to post comments.
- **The MCP server is the GitHub poster.** It validates codex's payloads against the schema below, then performs all GitHub writes (inline review comments, threaded replies, thread-resolution mutations, phase markers, cycle markers) from the host's authenticated `gh`.

`gc_codex_review` consumes a `===FINDINGS===…===END===` JSON tail from each reviewer's stdout. The MCP server validates each finding lexically (path lives inside the repo, line is positive or null, body is non-empty and within GitHub's 65535-char limit) and then POSTs each finding to `/repos/{owner}/{repo}/pulls/{pr}/comments` with the PR's current head SHA. The `[core]` / `[security]` reviewer label is prepended to the comment body by the poster; codex does not include it in the JSON.

Per-finding schema:

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `path` | string | yes | repo-relative, no leading `/`, no `..` segments |
| `line` | integer | yes | positive integer (file-level comments are not yet supported; every finding must anchor to a line in the diff) |
| `title` | string | yes | non-empty, ≤200 characters |
| `body` | string | yes | non-empty, ≤65322 characters (leaves headroom for the poster's `[reviewerLabel] title\n\n` prefix to keep the rendered comment under GitHub's 65535-char limit) |

The tool response carries both findings and write results, including any per-finding POST failures under `post_failures` (so callers can see partial-write conditions without parsing logs) and any per-reviewer parse errors under `parse_errors`.

`gc_codex_verify_finding` and `gc_codex_architecture_preflight` follow the same boundary: codex emits a structured decision (verify) or modifies design docs in-place (preflight); the MCP server posts the threaded reply, resolves the review thread, and writes phase markers from the host.
