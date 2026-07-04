# ADR-086: Research High-Risk Operation Authorization

## Status

Accepted

## Date

2026-07-03

## Context

`GC-RSCH-R005` and issue #1008 require generated code, browser activity,
lab/hardware actions, and external writes in research workflows to be treated as
high-risk operations that require sandboxing and explicit authorization. The
same issue also pulls in security, privacy, and prompt-injection robustness for
private manuscripts, PDFs, credentials, retrieved web content, metadata, and
generated actions.

The repository already has the adjacent owners:

- ADR-055 owns the skill-side research workflow and citation MCP.
- ADR-056 owns `ResearchIntake`, including `allowedTools` and
  `privacyConstraints`.
- ADR-064 owns `ResearchRun`, lifecycle gates, artifact manifests, durable
  checkpoints, and the rule that run-start policy must be snapshotted rather
  than read from mutable project intake.
- ADR-069 owns bounded provenance nodes and edges.
- ADR-071 owns source identity and interoperability boundaries.
- ADR-072 owns REST/MCP research tool surfaces.
- ADR-073 owns adapter/plugin boundaries and rejects arbitrary runtime code
  loading.
- ADR-083 owns the structured protocol plan and method-specific outputs.
- ADR-026, ADR-033, and ADR-037 own API authorization, audit actor provenance,
  and browser/session security.

Without a focused decision, likely failure modes are:

- treating `ResearchIntake.allowedTools` as approval to execute any action a
  tool can perform;
- treating free-text `privacyConstraints` as the executable privacy policy
  instead of resolving a structured, run-snapshotted egress policy;
- letting `AutonomyLevel.AUTONOMOUS` bypass operation-level authorization;
- conflating lifecycle gate decisions with concrete effect authorization;
- executing generated code, browser automation, lab actions, provider calls, or
  external writes from controllers or domain services;
- sending unpublished papers, private libraries, credentials, reviewer notes,
  proprietary PDFs, or private source material to AI providers, citation
  providers, Zotero, GitHub, cloud storage, browser targets, or other external
  services because a tool/provider is allowed in general;
- putting provider credentials, external-write payloads, local paths, raw source
  content, prompts, completions, browser cookies, or generated code bodies in
  error envelopes, logs, telemetry, graph properties, or audit rows;
- letting retrieved PDFs, web pages, or metadata act as instructions that can
  change tool policy, egress policy, sandbox policy, or approval state;
- creating a generic sandbox/plugin execution framework that duplicates the
  adapter boundary from ADR-073; and
- implementing separate REST, MCP, UI, and skill-side validators for the same
  high-risk action.

## Decision

### 1. High-risk operations are concrete effect requests, not tool labels

The initial high-risk operation kinds are a closed vocabulary:

- `GENERATED_CODE_EXECUTION`
- `BROWSER_ACTIVITY`
- `LAB_HARDWARE_ACTION`
- `EXTERNAL_WRITE`

Tool identifiers remain inventory and selection inputs. They do not by
themselves authorize a concrete effect. A high-risk operation request must name
the intended operation kind, tool/adapter identifier, run/stage context, the
bounded action summary, expected data egress, sandbox profile, destination or
target where applicable, and a retry-safe source action/idempotency identity.

Adding a new high-risk operation kind is an API-visible contract change. Do not
model operation semantics as free-text tool names, prompt instructions,
workspace filenames, or `RegisteredPlugin.metadata` keys.

### 2. Run policy is snapshotted and default-deny

Operation policy is run-scoped. At run start, the backend must snapshot the
policy inputs that affect high-risk operations, including allowed tool
identifiers, privacy/access restrictions, budget caps, and a structured data
egress policy. Later edits to `ResearchIntake` do not retroactively authorize
active or completed runs.

`allowedTools` answers "which tools may be requested"; it does not answer "may
this tool write externally, execute code, browse, use credentials, or touch
hardware now." The operation-policy resolver combines the run snapshot, selected
method/profile, privacy/access restrictions, data-egress policy, operation kind,
sandbox profile, target/destination, and adapter capability. The default is
deny unless the run policy and an explicit authorization record allow the
operation.

Privacy egress is destination-scoped. Material covered by `GC-RSCH-N006` -
unpublished papers, private libraries, credentials, reviewer notes, proprietary
PDFs, private source records, and private full text - must be represented as
bounded data classes or source/access states, then checked against a structured
run egress policy before any external service sees it. The policy decision must
bind at least data class, destination class or concrete destination, purpose,
operation kind, adapter/tool id, and whether derived summaries, identifiers,
hashes, or raw content are allowed. Absence of an allow rule means "local only."

Free-text `ResearchIntake.privacyConstraints` can be preserved as operator
context and displayed to users, but it cannot be the only enforcement input for
external egress. Implementations that need executable privacy behavior must add
structured policy fields or records under the same run-snapshot boundary rather
than parsing natural language, prompt text, reviewer notes, or workspace files
at execution time.

The current implementation must not rely on mutable `ResearchIntake` fields as
the runtime authority. ADR-064 already requires run snapshots for `allowedTools`
and `privacyConstraints`; R005 implementation must satisfy that contract before
operation authorization uses run state.

### 3. Explicit authorization is a durable run-scoped record

Every high-risk operation needs a durable authorization record before execution.
That record is research execution metadata, not a lifecycle gate and not a GRC
quality gate. It belongs under the research domain beside `ResearchRun` and may
reference the relevant run, stage, artifact attempt, provenance node, rationale
entry, or protocol-plan entry.

Authorization records carry bounded facts only: operation kind, tool/adapter id
and version, sandbox profile, requested egress class/destination, target system
or target class, expected artifact/reference ids, policy basis, approving actor
or denial actor, approval state, expiration/one-time-use metadata, source action
id or idempotency key, and short rationale/summary.

An authorization that permits external egress must name the allowed material
class and destination. It must not grant blanket provider access such as "send
all research context to model X" or "sync library to Zotero" without class,
destination, and purpose bounds. Authorizing derived metadata, hashes,
bibliographic identifiers, or short summaries is distinct from authorizing raw
PDF text, manuscript prose, reviewer notes, credentials, browser cookies, or
private library contents.

They must not store raw prompts, completions, generated code bodies, source PDFs,
web page bodies, metadata dumps, browser cookies, bearer tokens, Zotero secrets,
provider credentials, private absolute paths, lab command payloads, or external
write bodies.

`AUTONOMOUS` mode may propose a high-risk operation, but it cannot approve it.
Retrieved content, model output, provider output, and skill prose also cannot
approve it. Approval comes from an authenticated Ground Control actor through
the shared API/MCP authorization surface, and operation types that can use shared
credentials, mutate external systems, or affect lab/hardware default to an
operator/admin authorization route unless a later ADR narrows that safely.

### 4. Execution stays outside the domain service

The backend research service authorizes, records, and validates operation facts;
it does not execute generated code, launch browsers, shell out, poll providers,
call lab/hardware, perform GitHub/Zotero writes, or retry external side effects.

The executor boundary is an adapter or orchestrator from ADR-073. Executors must
present an unexpired, matching authorization record before performing the effect,
run with the declared sandbox profile, and report bounded outcome facts back
through structured service commands. Replays use the run-scoped source action id
or idempotency key and either reuse the existing outcome or fail as a conflict.

Sandbox profiles, adapter timeouts, network allowlists, browser roots, offline
source roots, lab endpoint allowlists, and credential references are
configuration boundaries. They use validated `@ConfigurationProperties` or the
existing MCP-server environment boundary. Secrets are referenced, never supplied
in API payloads, plugin metadata, prompts, process arguments, logs, Envers rows,
graph properties, or error bodies.

Generated code execution runs in an isolated workspace/container with no ambient
repo write access, network access, or secret environment unless the authorization
and sandbox profile allow it. Browser activity uses an isolated profile with no
ambient authenticated cookies unless explicitly authorized. Lab/hardware actions
go only through an operator-configured adapter with command/target allowlists.
External writes use curated adapter/MCP surfaces; privileged GitHub writes stay
in the MCP server's existing side-effect boundary, not in agent sandboxes or
domain services.

### 5. Retrieved content is untrusted input

PDFs, web pages, search results, source metadata, citation-provider payloads, and
browser-observed content are untrusted data. They may support provenance,
rationale, source identity, charting, or protocol-plan facts after structured
acceptance, but they cannot alter allowed tools, egress policy, sandbox policy,
authorization state, route selection, credentials, or executor command shape.

Any high-risk operation derived from retrieved content must be assembled from
accepted structured records and service-validated fields. Do not copy untrusted
instructions from retrieved content into shell commands, browser actions,
external-write payloads, or lab commands. Prompt-injection handling is therefore
an enforceable data-flow and authorization rule, not only skill prompt text.

### 6. Cross-cutting contracts remain shared

- **Auth:** REST routes stay under ADR-026/ADR-037. If a write can authorize
  credentialed external effects, external mutation, or lab/hardware action, gate
  it explicitly in `ApiPathMatrix` or a dedicated admin/operator route instead
  of relying on the generic authenticated research-run rule.
- **Validation:** REST DTOs use Bean Validation and Jackson enum binding; MCP
  mirrors use flat Zod schemas and body-field allowlists. Services own
  run-policy resolution, operation-kind compatibility, sandbox/egress checks,
  privacy data-class/source-state compatibility, same-run references,
  idempotency, expiration, one-time-use, and content bounds.
- **Errors:** use existing `GroundControlException` subclasses through
  `GlobalExceptionHandler` and `ErrorResponse`. Error details use stable codes
  and bounded field names, not raw content, payloads, paths, tokens, or command
  lines.
- **Actor provenance and audit:** approving actors come from `ActorFilter` /
  `ActorHolder` and Envers revision metadata. Request DTOs and MCP schemas must
  not accept caller-supplied audit actors.
- **Logging:** log low-cardinality fields such as project, run id, stage,
  operation kind, tool/adapter id, sandbox profile, egress class, destination
  class, state, source action id, and stable error code. Do not log raw content,
  prompts, generated code, external-write bodies, provider payloads, cookies,
  secrets, credentials, command lines, or private paths.
- **MCP:** curated research operation writes mirror REST through existing
  request helpers. MCP handlers do not execute the operation, parse workspace
  files, call providers, shell out, browse, or reimplement policy validators.
- **API/MCP drift:** public enums, action discriminators, body allowlists, and
  `gc_query` read allowlists follow ADR-034 and existing drift tests.
- **Testing and policy:** controller additions need `@WebMvcTest` slices; service
  tests cover deny-by-default policy, run-snapshot behavior, explicit approval,
  admin/operator routing where required, same-run references, idempotency,
  expired/consumed approvals, sandbox/egress incompatibility, untrusted-content
  rejection, and content-leak guards. Repo completion still runs `make policy`.

### 7. Extensibility seam

The extension seam is the tuple of operation kind, adapter capability,
sandbox profile, egress policy, target/destination class, and policy resolver.
Future operation families or sandbox modes should extend that vocabulary and its
validators. They should not introduce a universal
`ResearchExtension.execute(Map)` API, one controller per provider, arbitrary
runtime code loading, prompt-only enforcement, or direct executor side effects in
controllers, MCP handlers, or domain services.

## Consequences

### Positive

- R005 gets an enforceable authorization boundary that composes with research
  run lifecycle, provenance, rationale, protocol-plan, REST, MCP, audit, and
  adapter decisions.
- Tool allowance, egress policy, sandbox policy, and concrete approval remain
  separate concepts.
- Prompt-injection controls become service/enforcer rules over untrusted data
  and high-risk effects rather than advisory prose.

### Negative

- A high-risk operation needs one more durable record before execution.
- Executors must check authorization and report bounded outcomes instead of
  treating the agent transcript or local workspace state as authority.
- Admin/operator routing may be needed for some operation types even when normal
  research-run mutations are authenticated-only.

### Risks

- If the run does not snapshot allowed tools and privacy/egress policy, a later
  intake edit can silently change what an active run may execute.
- If `allowedTools` is treated as authorization, a broad tool label can permit
  unintended browser actions, code execution, or external writes.
- If an authorization id is reusable without expiration or consumed-state checks,
  a retry can become an unintended repeat side effect.
- If sandbox profiles or egress policy are free text, executors will enforce
  different rules.
- If privacy classes and destination classes are not explicit, an allowed tool
  can accidentally become a broad external disclosure permission for unpublished
  or proprietary research material.
- If retrieved content is allowed to shape commands directly, prompt injection
  can bypass the durable policy records.
- If errors, logs, telemetry, graph projections, or audit rows echo operation
  payloads, private research content or credentials can leak.

## Non-Goals

- No implementation of entities, migrations, controllers, DTOs, MCP tools,
  frontend views, executors, provider adapters, browser automation, lab/hardware
  integration, or sandbox runtime in this ADR.
- No generic workflow engine, approval engine, sandbox framework, dynamic plugin
  execution system, dependency resolver, credential store, or provider
  marketplace.
- No storage decision for full text, PDFs, generated code bodies, browser traces,
  command transcripts, raw provider payloads, prompts, completions, or external
  write bodies.
- No replacement of ADR-064 lifecycle gates, ADR-069 provenance, ADR-072
  REST/MCP surfaces, ADR-073 adapter boundary, or ADR-083 protocol-plan gates.
- No new authentication model, actor override mechanism, exception hierarchy,
  error envelope, logging stack, enum-mirror system, GitHub side-effect path, or
  token-in-argv path.

## Related Requirements

- `GC-RSCH-R005` - treat generated code, browser activity, lab/hardware actions,
  and external writes as high-risk operations requiring sandboxing and explicit
  authorization.
- `GC-RSCH-N005` - security.
- `GC-RSCH-N006` - privacy.
- `GC-RSCH-N014` - robustness to prompt injection.

## Related Issues

- #1008 - Research privacy, security, and prompt-injection controls.
- #1029 - Research adapter/plugin boundary.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-037 - Browser Session Access Control.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-056 - Research Project Type and Intake Metadata.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-069 - Research Artifact Provenance Ledger.
- ADR-071 - Research Interoperability and Source Identity Boundary.
- ADR-072 - Research REST and MCP Tool Surface.
- ADR-073 - Research Extensibility and Adapter Boundary.
- ADR-083 - Research Protocol Plan Artifact and Method-Specific Outputs.
