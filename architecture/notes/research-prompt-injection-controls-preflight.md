# Research Prompt-Injection Controls Preflight

Issue: #1008
Requirement: GC-RSCH-N014

This note records architecture guardrails for treating retrieved papers, web
pages, PDFs, search results, citation-provider payloads, browser-observed
content, and source metadata as untrusted input. It is not an implementation
plan.

## Boundary

GC-RSCH-N014 is a data-flow and authorization requirement. It should not become
a new prompt template, content-sanitizer abstraction, source store, provider
parser, workflow engine, or instruction-firewall service.

Keep these states separate:

- **Raw retrieved content:** PDF bytes/text, HTML, abstracts, metadata payloads,
  search-result bodies, provider responses, browser page content, and local
  file contents. These are untrusted inputs at adapter or workspace boundaries.
- **Accepted bounded facts:** source identifiers, access state, counts,
  locators, content hashes, short summaries, provenance node references,
  rationale summaries, protocol-plan coverage facts, and lifecycle artifact
  manifest records accepted by the research services.
- **Control-plane policy:** allowed tool ids, egress policy, sandbox profile,
  route selection, credential reference, authorization state, and executor
  command shape.
- **High-risk effects:** generated code execution, browser activity,
  lab/hardware actions, and external writes under ADR-086.

Only accepted bounded facts may enter durable research records. Raw retrieved
content can inform a fact after structured acceptance, but it must never alter
control-plane policy or directly assemble a high-risk effect. Retrieved
instructions to ignore prior directions, use a token, post a manuscript, or run
a command remain source content, not authority.

## Incumbents To Reuse

- ADR-086 is the binding decision for prompt-injection handling, structured
  egress policy, and high-risk operation authorization.
- ADR-064 owns `ResearchRun`, lifecycle gates, artifact manifests,
  checkpoint/resume, and run-snapshotted intake values. Do not gate later stages
  from workspace files or retrieved-content instructions.
- ADR-069 owns the research provenance ledger. Provenance nodes/edges record
  bounded references and short summaries; they are not a raw content store.
- ADR-071 owns provider-neutral source identity and interoperability. Provider
  payloads, Zotero items, PDFs, bibliography files, Git/local files, and source
  records are related but distinct concepts.
- ADR-072 owns the research REST/MCP surface. REST controllers stay thin over
  services, and MCP tools mirror REST through curated action schemas.
- ADR-073 owns adapter/plugin boundaries. Provider/search/browser/filesystem
  effects happen at infrastructure or MCP adapter boundaries, not in domain
  services or controllers.
- ADR-055 and `mcp/citation` remain the deterministic citation and OA-PDF
  ingress path. They validate bibliographic/PDF acquisition policy, but they do
  not become the prompt-injection enforcement layer.
- `ResearchRunService`, `ResearchProvenanceService`, and the research command
  DTOs are the semantic validators for research facts.
- `ProjectService` remains the project-scope resolver. Cross-project misses
  should be concealed as not found.
- `ApiPathMatrix`, `IpAllowlistFilter`, `BearerTokenAuthFilter`,
  `BrowserSecurityConfig`, and `ApiSecurityConfig` remain the auth surface.
- `ActorFilter`, `ActorHolder`, and Envers remain the actor/audit provenance
  surface. Caller-supplied actor fields are not audit authority.
- `GlobalExceptionHandler`, `ErrorResponse`, and existing
  `GroundControlException` subclasses remain the error contract.
- `@ConfigurationProperties` records registered by
  `GroundControlApplication` remain the configuration boundary for non-secret
  timeouts, allowlists, sandbox profiles, and adapter settings.
- `mcp/ground-control/lib.js`, `pick`, `reqArg`, `RequestError`,
  `parseErrorBody`, `TO_CAMEL`, `OPAQUE_VALUE_KEYS`, `gc_research_run`,
  `gc_research_provenance`, `gc_query`, and the OpenAPI/MCP drift tests remain
  the MCP shaping and drift-control incumbents.
- The data-classification lattice is the existing pattern for deterministic
  information-flow policy. If research egress needs labeled data classes and
  destination classes, reuse that style of explicit policy and validation; do
  not silently treat architecture-model labels as research-material labels
  without a deliberate mapping.

## Cross-Cutting Layers

- **Auth surface:** research routes stay under `/api/v1/**` and the shared
  bearer/browser chains. Any write that authorizes credentialed external
  effects, external mutation, browser activity, code execution, or lab/hardware
  action needs an explicit `ApiPathMatrix` decision; admin/operator routing is
  the safe default.
- **Project and run scope:** controllers resolve a project through
  `ProjectService`; services verify the run, artifact, provenance node,
  rationale entry, protocol-plan entry, source record, and authorization record
  all belong to the same project/run.
- **Input shape:** REST DTOs use Jackson enum binding and Bean Validation. MCP
  mirrors use flat Zod schemas and action-specific body allowlists. Unknown
  fields are dropped or rejected; they are not tunneled through `metadata`.
- **Untrusted-input acceptance:** raw retrieved content enters only through a
  parser/adapter boundary that emits structured candidate facts. Services decide
  whether those facts are acceptable for source identity, provenance, rationale,
  protocol coverage, artifact readiness, or operation authorization.
- **Policy gate:** allowed tool ids, privacy/access restrictions, egress rules,
  sandbox profile, destination class, adapter capability, and operation kind
  must be resolved from run-snapshotted policy and structured records. Free-text
  `privacyConstraints`, prompts, reviewer notes, source text, metadata, or skill
  prose cannot be the executable policy.
- **High-risk authorization:** ADR-086 operation authorization is required
  before any generated code execution, browser activity, external write, or
  lab/hardware action. Retrieved content and autonomous mode may suggest an
  operation, but they cannot approve it.
- **Secret handling:** credentials are referenced, not supplied in request
  bodies, plugin metadata, prompts, source records, provenance rows, Envers
  rows, graph properties, logs, MCP responses, error envelopes, temp file names,
  or process argv.
- **OS/runtime exposure:** domain services and controllers do not shell out,
  browse, call providers, launch code, or write externally. Executors or
  adapters must use bounded configuration, no ambient secrets, and no
  command-line construction from retrieved content.
- **Persistence:** durable records store bounded metadata only: ids, enum names,
  counts, hashes, locators, short summaries, policy versions, source action ids,
  and idempotency keys. They do not store raw PDFs, HTML, full text, abstracts
  used as substitutes, prompt/completion bodies, generated code bodies,
  provider payloads, browser cookies, command lines, or private absolute paths.
- **Error envelope:** validation and policy failures use existing domain
  exceptions through `GlobalExceptionHandler` and `ErrorResponse`. Error details
  name stable fields/codes and must not echo raw content, payloads, URLs with
  secrets, paths, command lines, provider response bodies, or tokens.
- **Logging and observability:** use SLF4J low-cardinality fields such as
  project, run id, stage, operation kind, input kind, adapter id, data class,
  destination class, sandbox profile, status, source action id, and error code.
  Do not log retrieved content, summaries copied from content, prompts,
  manuscripts, generated code, provider payloads, secrets, cookies, command
  lines, or private paths.
- **API/MCP drift and tests:** new public enums, request fields, body allowlists,
  `gc_query` paths, and MCP actions must update the existing drift inventories.
  Controller additions need `@WebMvcTest` slices; service tests should cover
  default-deny policy, run-snapshot behavior, same-run references, content-leak
  guards, idempotency, and rejection of untrusted content shaping commands or
  policy.

## Extensibility Seam

The seam is the structured tuple that lets a future adapter or executor prove
what is being accepted or authorized:

- input kind, adapter id/version, and source action id;
- accepted record kind and project/run/stage context;
- source/access state and data class;
- destination or destination class;
- operation kind and target class;
- sandbox profile and policy version;
- idempotency key and bounded outcome summary.

New source formats, providers, browser capture modes, source stores, full-text
Q&A adapters, extraction schemas, or renderer outputs should extend this tuple
and its validators. They should not add one-off controllers, parallel MCP
validators, provider-specific persistence tables, free-form metadata authority,
or a universal `execute(Map)` extension API.

## Gotchas And Anti-Patterns

- Do not treat prompt wording as the control. The backend must enforce data-flow
  and authorization rules even if no model prompt contains a warning.
- Do not treat `ResearchIntake.allowedTools` as approval for every action a tool
  can perform.
- Do not parse free-text `privacyConstraints` into executable egress policy.
- Do not let source metadata or PDF/webpage text choose routes, policies,
  credentials, sandbox profiles, command arguments, or destination URLs.
- Do not store raw content in `ResearchRunArtifact`, provenance, rationale,
  disclosure, graph projection, telemetry, audit rows, or broad API/MCP
  responses.
- Do not add generic `metadata` bags for data classes, destinations,
  authorization, sandbox profiles, or source disposition when typed fields are
  needed.
- Do not create a second exception hierarchy, error envelope, auth filter, actor
  mechanism, provider credential store, MCP transport, or validation stack.
- Do not call citation providers, Zotero, Git, browsers, shells, local
  filesystem scanners, or external write targets from controllers or domain
  services.
- Do not make graph projection, `workflow_phase_event`, Envers history, local
  workspace files, or skill transcripts the authority for prompt-injection
  safety.

## Non-Goals

- No implementation of entities, migrations, controllers, DTOs, MCP tools,
  frontend views, provider adapters, browser automation, executors, sandbox
  runtime, or policy records in this note.
- No new full-text/PDF/manuscript/provider-payload store.
- No replacement of ADR-055 citation MCP, ADR-064 lifecycle, ADR-069 provenance,
  ADR-071 source identity, ADR-072 REST/MCP surface, ADR-073 adapter boundary,
  or ADR-086 high-risk operation authorization.
- No generic workflow engine, approval engine, prompt firewall, dynamic plugin
  execution framework, credential store, or provider marketplace.
- No new authentication model, actor override mechanism, exception hierarchy,
  error envelope, logging stack, enum-mirror system, GitHub side-effect path, or
  token-in-argv path.
