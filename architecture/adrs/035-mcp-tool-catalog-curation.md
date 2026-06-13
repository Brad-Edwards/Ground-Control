# ADR-035: MCP Tool Consolidation and Read-Only Query Escape Hatch

## Status

Accepted

## Date

2026-05-11

## Context

Issue [#628](https://github.com/KeplerOps/Ground-Control/issues/628) (audit
finding A12-03, severity:medium) flagged that the Ground Control MCP server
registered 215 tools across 39 sections in `mcp/ground-control/index.js`.
Every tool's name, description, and Zod input schema is loaded into every
connected LLM client at session start, regardless of whether the session is
doing requirements work, an `/implement` run, risk modelling, or pack-registry
administration.

The surface was the result of mirroring every REST endpoint as its own
named MCP tool—`gc_create_X`, `gc_get_X`, `gc_list_X`, `gc_update_X`,
`gc_delete_X` for thirty-plus entities, plus seven near-identical graph-
traversal tools, plus history / timeline / export / diff variants for many
of them. Three failure modes followed:

1. **Catalog noise.** Tens of KB of tool definitions in the LLM context on
   every connect. The seven graph-traversal tools alone (`gc_get_relations`,
   `gc_get_descendants`, `gc_get_ancestors`, `gc_find_paths`,
   `gc_find_graph_paths`, `gc_extract_subgraph`, `gc_traverse_graph`,
   `gc_analyze_impact`) all answer the same shape of question; an agent that
   does not already know which one to call burns calls discovering it. The
   pattern repeats for asset, risk, threat-model, and control families.
2. **Maintenance burden.** Every new backend endpoint required a new MCP
   tool registration, a new Zod schema, a new test, and another line in the
   LLM-visible catalog. Endpoint additions naturally accelerate this; the
   surface had grown from 203 (audit time) to 215 at the time of this ADR.
3. **Long-tail self-service gap.** No curation could ever cover every "I
   have a hypothesis, let me check it" join an agent might need; the only
   escape was "load more tools," which makes the noise problem worse.

The accepted boundaries (preflight notes, ADR-026, ADR-027, ADR-029,
ADR-031, ADR-032, ADR-034) constrain the solution:

- The Spring REST API (governed by ADR-026's bearer-token + path-matrix
  authorization chain) is the semantic authority. MCP tools are adapters
  over it; they must not become a second controller layer.
- Workflow tools that encode `/implement` gates must remain first-class and
  named: `gc_get_repo_ground_control_context`, `gc_codex_architecture_preflight`,
  `gc_post_implementation_plan`, `gc_codex_review`, `gc_codex_verify_finding`
- Hiding a tool from a client catalog is a usability decision, not an
  authorization decision. Authorization remains the backend's responsibility.
- The MCP adapter must reuse `buildUrl`, `addAuthorizationHeader`, `request`,
  `RequestError`, and `parseErrorBody` from `mcp/ground-control/lib.js`.
- The READ-only escape hatch must be path/method allowlisted, relative
  `/api/v1/...` only, and not accept caller-supplied headers or methods.
- Cypher passthrough is forbidden by ADR-032; any read-only escape hatch
  must be REST-only.

## Decision

Adopt a **two-part design**: aggressive **consolidation** of named MCP tools
plus a **read-only `gc_query` escape hatch** for the long tail.

### 1. Consolidation: one tool per entity, action-discriminated

The 215 `server.tool(...)` registrations collapse to ~26 named tools. The
target is "drastic reduction without capability loss." Every backend
operation reachable through MCP today remains reachable; what changes is
how many distinct tool names sit in the LLM-visible catalog.

**Workflow primitives (kept by name—the `/implement` and `/ship` skills
call these by name in their SKILL.md prose):**

- `gc_get_repo_ground_control_context`, `gc_codex_architecture_preflight`,
  `gc_post_implementation_plan`, `gc_codex_review`, `gc_codex_verify_finding`,
  `gc_create_github_issue`, `gc_dashboard_stats`, `gc_query`,
  `gc_get_requirement`, `gc_get_traceability`, `gc_get_traceability_by_artifact`,
  `gc_create_traceability_link`, `gc_delete_traceability_link`,
  `gc_transition_status`, `gc_bulk_transition_status`.

**Consolidated entity tools (one per entity, action-discriminated):**

- `gc_requirement`—list, create, update, delete, archive, clone.
- `gc_relation`—create, get, delete.
- `gc_adr`—create, update, delete, transition, requirements.
- `gc_document`—create, update, delete, grammar_set, grammar_delete, reading_order.
- `gc_section`—create, update, delete, tree, content_add, content_update, content_delete.
- `gc_asset`—create, update, delete, archive, relation_create, relation_delete,
  detect_cycles, impact_analysis, extract_subgraph, link_create, link_delete,
  external_id_create, external_id_update, external_id_delete.
- `gc_observation`—create, update, delete, latest.
- `gc_risk_scenario`—create, update, delete, transition, requirements,
  link_create, link_delete.
- `gc_threat_model`—create, update, delete, transition, link_create, link_delete.
- `gc_control`—create, update, delete, transition, link_create, link_delete.
- `gc_risk_governance`—covers methodology_profile, risk_register_record,
  risk_assessment_result, treatment_plan, verification_result; action discriminator
  is `{entity, action}`.

**Compute-heavy operations (kept as their own tools, mode-discriminated):**

- `gc_analyze`—cycles, orphans, coverage_gaps, impact, cross_wave, consistency,
  completeness, status_drift, similarity, work_order.
- `gc_graph`—ancestors, descendants, paths, find_paths, subgraph, visualization,
  traverse.
- `gc_baseline`—create, delete, snapshot, compare.
- `gc_quality_gate`—create, update, delete, evaluate.

**Admin / deployment operations (opt-in via `GC_MCP_ADMIN=1`):**

- `gc_admin`—import_strictdoc, import_reqif, sync_github, sync_github_prs,
  embed_requirement, embed_project, embedding_status, materialize_graph,
  create_project, list_projects, run_sweep, run_sweep_all, export_*.
- `gc_pack`—subsystem-discriminated `{subsystem, action}` over plugin,
  control_pack, registry, trust_policy, install. Includes the read actions
  (`list_*`, `get_*`, `list_pack_versions`) that previously had their own
  named tools—`gc_query` cannot reach `/api/v1/pack-registry/**` (the path
  is denylisted), so pack reads stay on the named tool.

The admin gate (a single `GC_MCP_ADMIN` env-var check) is the only piece of
catalog-style filtering that survives the consolidation. Previously the
entire MCP surface ran through a catalog filter; with the consolidated
surface, gating only matters for the admin-shaped operations—every other
tool is safe to register unconditionally because it lacks a `ROLE_ADMIN`-
gated path.

**Pure GETs move onto `gc_query`.** History, timeline, exports, list-by-X,
get-by-id for niche entities—none of these get their own MCP tool. The
agent issues a `gc_query` call with the appropriate `/api/v1/*` path.

**Action discriminator schema shape.** Each consolidated tool registers
with a `z.object({...}).strict()` schema where one required field
(`action`, `kind`, `mode`, `subsystem`, or `entity` + `action`) is a Zod
enum and all action-specific fields are optional at the schema level. The
handler dispatches on the discriminator and validates required fields per
action with a small `reqArg(args, key, action)` helper that throws a
descriptive error when a required field is missing. The tool's description
enumerates the actions and the per-action required fields so the LLM has
the contract in context.

**Why not a strict `discriminatedUnion` schema?** The MCP SDK's input-
shape acceptance varies; a flat ZodObject with optional fields is robust
across SDK versions. The handler-side `reqArg` check is the actual gate.
The cost is slightly looser type information in the JSON Schema sent to
clients, but the description text fills the gap.

### 2. `gc_query` (read-only REST escape hatch)

A new MCP tool registered always (it is part of the workflow primitives
above). It accepts:

- `path`—a relative path matching the strict regex `^[A-Za-z0-9/_.-]+$`,
  starting with one of an explicit allowlist of `/api/v1/*` prefixes.
  Absolute URLs, protocol-relative URLs, paths containing `..`, paths
  containing `%` (encoded-dot bypass guard), query strings or fragments
  embedded in the path, and any path outside the allowlist are rejected
  before any network call.
- `params`—an optional flat object of query parameters. Values must be
  `string | number | boolean | null`. Arrays and nested objects are
  rejected.

The tool **only issues GET requests**. There is no `method` parameter; HTTP
verb cannot be supplied. Headers cannot be supplied; `addAuthorizationHeader`
adds the bearer token, and the request always emits
`Accept: application/json` plus `X-Actor: mcp-server`.

**Path allowlist** (canonical source: `GC_QUERY_PATH_ALLOWLIST` in
`mcp/ground-control/gc-query.js`): `/api/v1/adrs`, `/api/v1/analysis`,
`/api/v1/assets`, `/api/v1/audit`, `/api/v1/audits`, `/api/v1/baselines`,
`/api/v1/control-effectiveness-assessments`, `/api/v1/control-tests`,
`/api/v1/controls`, `/api/v1/dashboard`, `/api/v1/derivations`, `/api/v1/documents`,
`/api/v1/evidence-artifacts`, `/api/v1/findings`, `/api/v1/graph`,
`/api/v1/methodology-profiles`, `/api/v1/observations`, `/api/v1/projects`,
`/api/v1/quality-gates`, `/api/v1/relations`, `/api/v1/requirements`,
`/api/v1/risk-assessment-results`, `/api/v1/risk-register-records`,
`/api/v1/risk-scenarios`, `/api/v1/sections`, `/api/v1/test-cases`,
`/api/v1/test-plans`, `/api/v1/test-runs`, `/api/v1/test-suites`, `/api/v1/threat-models`, `/api/v1/timeline`,
`/api/v1/traceability`,
`/api/v1/treatment-plans`, `/api/v1/verification-results`. A drift-catch test
compares this list against the README and this ADR on every test run.

**Denylist:** `/api/v1/admin/**`, `/api/v1/embeddings/**`,
`/api/v1/analysis/sweep/**`, `/api/v1/pack-registry/**` are rejected even
though they live under `/api/v1/`. Backend auth (ADR-026) would also reject
them; the denylist is defense in depth and produces clearer errors.

**Cost cap.** 30-second timeout via `AbortController` whose signal stays
armed through body consumption (the timer is cleared only when
`executeGcQuery` returns). 1 MiB response-body cap enforced by streaming
the body and canceling the reader once the cap is reached—the request
does NOT buffer unbounded data before truncating. The cap covers the full
request lifetime, not just header arrival.

**Public argument boundary.** `gc_query`'s public contract is exactly
`path` plus optional `params`; `headers`, `method`, `body`, caller-supplied
tokens, and any other user-facing expansion remain rejected. Transport or SDK
control fields injected outside the caller's JSON payload, such as an
AbortController `signal`, are not part of that public contract and must be
normalized away before the public-argument allowlist decides whether an
unknown key is user supplied. This is the seam between MCP runtime plumbing
and Ground Control's adapter contract: accepting a known internal control
field must not become `.passthrough()` business logic for arbitrary keys, and
unknown user keys must still fail with `invalid_query_args`.

**Error semantics.** Validation errors are wrapped as `RequestError` with
`status: 0` and codes `invalid_query_path`, `invalid_query_params`,
`invalid_query_args`, or `gc_query_timeout`, so the existing `err()`
renderer in `index.js` surfaces them in the same envelope shape as backend
errors.

## Consequences

### Positive

- **86 % reduction in the LLM-visible tool catalog** (215 → ~30 named
  tools) without losing any backend capability. Every endpoint reachable
  before remains reachable, either through a consolidated named tool or
  through `gc_query`.
- The seven-way graph-traversal cluster collapses into one `gc_graph` tool
  with a mode discriminator. The CRUD-five-per-entity pattern collapses
  into one tool per entity. The maintenance overhead of "one tool per
  endpoint" becomes "one tool per entity."
- `gc_query` closes the long-tail "let me check this hypothesis" gap
  without expanding the write surface or letting agents construct
  Cypher (ADR-032). An agent that finds no curated tool covering a join
  it needs can issue a constrained GET and read the answer.
- Workflow tools the `/implement` skill calls by name are unchanged. The
  skill prose does not need to be rewritten in this PR.

### Negative

- Each consolidated tool's description is longer than the single-purpose
  tools it replaces, because it has to enumerate actions and per-action
  required fields. Net LLM context still drops sharply because the count
  drops dramatically; per-tool size is larger but the count multiplier is
  far smaller.
- The handler-side `reqArg(args, key, action)` validation is runtime, not
  static. The LLM-visible JSON Schema does not encode "field X is required
  when action=create." Agents have to read the description.
- `gc_query`'s path allowlist must be maintained as the backend surface
  evolves. New read endpoints land outside the agent's reach until someone
  adds them to the allowlist. This is intentional (default-deny); the
  README documents the maintenance step.

### Risks

- **Action-set drift.** A new action added to an entity's lib.js function
  needs to be added to the corresponding consolidated tool's enum + switch.
  An action with no test coverage that drops out of the switch will fail
  silently at runtime. Mitigation: the action enum is the only source of
  truth in the schema; if a switch case is missing for a listed enum
  value, the handler's default branch returns an "Unknown action" error,
  which is loud at first call.
- **Allowlist lag.** A new `/api/v1/**` read endpoint will be unreachable
  via `gc_query` until the allowlist is updated. Default-deny is correct;
  the README documents this.
- **Body truncation hides material data.** 1 MiB is ample for typical
  lookups; large exports use the dedicated `gc_admin` export actions.
- **Runtime metadata drift.** MCP SDKs may add non-user control fields to
  handler argument objects. The adapter must maintain a deliberately tiny
  transport-metadata normalization seam instead of relaxing the public
  contract for all unknown keys. Regression tests should cover both sides:
  known internal metadata is ignored, while `headers` and `method` remain
  rejected.

## Alternatives considered

- **Hide-by-default via env-var-selected catalog tags** (the original
  approach in an earlier draft of this PR). Rejected: it reduces LLM
  context per session but does not reduce the maintenance surface; the
  source still has 215 tools to keep working. The user explicitly asked
  for "drastic reduction" and "lose complexity, not capability."
- **Generic `gc_call_api(path, method, body)` escape hatch covering
  writes too.** Rejected: an agent that can issue arbitrary writes via
  a generic proxy bypasses the per-action Zod schema that captures the
  domain shape. Splitting into a read-only `gc_query` plus action-
  discriminated write tools preserves the schema contract on the write
  side.
- **Cypher passthrough via `gc_query`.** Rejected by ADR-032.
- **Strict `z.discriminatedUnion`** per tool. Rejected: SDK acceptance
  is variable; flat ZodObject + handler-side `reqArg` is robust and the
  description carries the contract.

## References

- Issue [#628](https://github.com/KeplerOps/Ground-Control/issues/628)—A12-03 audit finding
- ADR-026 (REST API Access Control)—backend auth chain
- ADR-027 (Agent-Neutral Implement Workflow Packaging)—workflow tool names
- ADR-029 (Issue-Thread Gate Model)—durable record
- ADR-031 (Codex Review Stopping Model)—review cycle caps
- ADR-032 (AGE Query Construction Boundary)—why Cypher passthrough is forbidden
- ADR-034 (API Enum Contract Single Source)—enum mirrors preserved
- `architecture/notes/mcp-tool-catalog-surface-preflight.md`—preflight guardrails
- `mcp/ground-control/gc-query.js`: `gc_query` validators, allowlist, denylist, streaming cap
- `mcp/ground-control/index.js`—consolidated tool registrations (~30 tools, down from 215)
