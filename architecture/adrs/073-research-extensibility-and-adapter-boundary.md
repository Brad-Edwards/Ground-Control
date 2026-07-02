# ADR-073: Research Extensibility and Adapter Boundary

## Status

Accepted

## Date

2026-06-29

## Context

`GC-RSCH-N010` requires research methods, search providers, reviewers,
extraction schemas, writing templates, and output formats to be plugin-like.
The adjacent research decisions already define the durable lifecycle and data
surfaces:

- ADR-055 owns the skill-side workflow, methodology catalog, and citation MCP.
- ADR-056 owns research project type and intake.
- ADR-064 through ADR-070 own run lifecycle, observability, review/rationale
  records, disclosure, provenance, and graph projection.
- ADR-071 owns provider-neutral source identity and format/provider
  interoperability.
- ADR-072 owns the REST/MCP research tool surface and rejects a second adapter
  framework for tool routing.

The repository also has incumbents that should shape this work rather than be
replaced: ADR-023's `PluginRegistry`, typed adapter registries such as
`DerivationAdapterRegistry` and `EvidenceCollectionAdapterRegistry`, existing
document export services, and the REST/MCP validation and error-handling
contracts.

Without a specific extensibility boundary, likely failure modes are:

- treating "plugin-like" as arbitrary runtime code loading;
- putting provider/search/reviewer side effects in controllers, MCP handlers,
  or domain services;
- using `RegisteredPlugin.metadata` as an untyped semantic schema for lifecycle
  gating or source validation;
- creating one schema, aggregate, or controller per provider, bibliography
  format, method, or output format;
- making prompt text or skill prose the enforcement layer for extension
  behavior;
- bypassing Bean Validation, service validation, `ErrorResponse`,
  `ActorHolder`, Envers, MCP Zod schemas, or OpenAPI/MCP drift gates;
- leaking provider credentials, Git remotes, local paths, raw source content,
  prompts, manuscripts, or provider payloads through logs, errors, graph
  properties, MCP responses, or process arguments.

## Decision

### 1. "Plugin-like" means registered, typed, selectable, and bounded

Research extensibility uses stable extension identifiers, versions,
capabilities, and typed contracts. It does not mean untrusted code is loaded or
executed at runtime.

The existing `PluginRegistry` remains the inventory and lifecycle metadata
surface for classpath plugins and dynamic registrations. Dynamic registration is
metadata-only unless the backend already has a compiled, trusted implementation
for the corresponding typed adapter. The registry is not a package installer,
dependency solver, script runner, provider client, or policy engine.

For API-visible plugin categories, extend the existing `PluginType` vocabulary
at category granularity only. Do not add one enum value per provider, method, or
format. Specific capabilities such as `provider:crossref`, `format:bibtex`, or
`method:scoping` belong in capability/metadata fields or category-specific
records, not as new plugin types.

### 2. Each research extension family has a narrow semantic owner

The six N010 extension families stay separate because they answer different
product questions:

| Family | Boundary |
|---|---|
| Methods | Versioned method profiles/catalog entries that feed intake defaults, gate policy, source-state rules, and artifact/schema requirements. They are data and policy inputs, not provider clients. |
| Search providers | Infrastructure adapters behind a domain port. They call Crossref, OpenAlex, Unpaywall, arXiv, PubMed, Zotero, local indexes, or offline stores only at the adapter boundary and return normalized source/import commands. |
| Reviewers | Recommendation adapters that produce bounded review, gate, or rationale suggestions. They cannot approve gates, resolve comments, set audit actors, or persist lifecycle state directly. |
| Extraction schemas | Versioned charting/extraction schema definitions plus validators. They shape accepted charting/provenance data; they are not arbitrary JSON bags that bypass service validation. |
| Writing templates | Versioned rendering templates selected by id/version. They consume accepted artifacts/provenance/rationale data and do not become prompt-only policy enforcement. |
| Output formats | Export/render adapters over canonical research/document records. Where the format overlaps existing document export (`DocumentExportService` and format-specific services), reuse that export boundary rather than creating a parallel research document stack. |

Do not introduce a universal `ResearchExtension.execute(Map)` abstraction. Use a
typed port or data registry only when the family needs behavior, validation, or
selection independently of the others.

### 3. Selection is explicit and historical

When a research run, stage action, artifact, source import, extraction, review,
or export depends on an extension, the accepted command records the selected
extension id and version as bounded metadata. That snapshot is part of the
run's history; later plugin/catalog/template upgrades do not rewrite existing
runs or silently change replay/resume behavior.

The extensibility seam is therefore the stable `extensionId` / `version` /
`capability` tuple plus parameters specific to each extension family, not Java class names,
provider-specific payloads, free-text filenames, or workspace-local prompt
instructions.

### 4. Adapters normalize; services validate and persist

Adapters may acquire external data or render output, but they do not own
research business state. A provider, reviewer, extractor, or renderer returns a
typed result that the research service accepts or rejects through the same
command DTO and aggregate rules as REST and MCP writes.

Controllers stay thin: resolve project/run identity, bind and validate request
DTOs, and call services. Domain services own project scoping, run status, stage
legality, gate policy, source disposition, provenance/rationale consistency,
idempotency, and content bounds. Repositories own project-scoped queries.

Infrastructure implementations own external calls, local filesystem access,
Git/local index access, parsers, and renderers. They must not be imported by
`api/` and must not pull Spring Web concerns into `domain/`.

### 5. Cross-cutting layers remain the enforcement boundary

Research extensibility must reuse the repo's existing cross-cutting layers:

- **Auth:** REST surfaces stay under ADR-026 bearer/browser security and
  `ApiPathMatrix`; cross-project misses are concealed as not found.
- **Validation:** REST DTOs use Bean Validation and Jackson enum binding; MCP
  tool inputs use Zod; services own semantic validation and content bounds.
- **Errors:** failures use existing `GroundControlException` subclasses through
  `GlobalExceptionHandler` and `ErrorResponse`. No research-specific error
  envelope.
- **Audit and actors:** mutation actors come from `ActorFilter` / `ActorHolder`
  and Envers revision metadata. Reviewer/provider metadata cannot override the
  authenticated actor.
- **Logging:** use SLF4J with low-cardinality fields: project, run id, stage,
  extension id/version, provider, schema id/version, template id/version,
  format, action id, counts, and stable error code. Do not log raw content,
  prompts, provider payloads, secrets, private paths, or credential-bearing
  remotes.
- **Configuration and OS exposure:** provider keys, Zotero settings, network
  timeouts, offline roots, parser toggles, and renderer options use validated
  `@ConfigurationProperties` or the existing MCP-server environment boundary.
  Do not put secrets in API payloads, plugin metadata, process argv, Envers rows,
  graph properties, logs, or error bodies.
- **MCP:** curated research write tools mirror REST through flat Zod schemas,
  existing request helpers, and body-field allowlists. Read-only ad hoc access
  uses `gc_query` allowlisted `/api/v1/**` paths. MCP handlers do not parse
  workspace files, shell out, call providers, or implement parallel validators.
- **API/MCP drift:** API-visible enums, body allowlists, and write shapes follow
  ADR-034/OpenAPI contract checks. Adding a new extension category or command is
  a contract change, not an unreviewed metadata key.

### 6. Security posture for extension execution

No v1 research extension executes operator-supplied code. Classpath plugins are
trusted application code. Dynamic plugin rows can advertise availability,
capabilities, configuration references, or installed-pack metadata, but they
cannot point to a shell command, arbitrary file, arbitrary URL, Git remote, or
dependency artifact that the backend executes.

If a future requirement needs dynamic code loading, it needs a separate ADR for
artifact resolution, trust policy, sandboxing, signature verification,
dependency isolation, privilege boundaries, audit records, and rollback.

Provider and local/offline adapters must fail closed on unsafe inputs:
configured roots only, realpath checks for filesystem access, bounded read
sizes, structured parsers for BibTeX/RIS/CSL/markdown where available, relative
locators in product records, and no direct token or path echo in logs/errors.

## Consequences

### Positive

- N010 can evolve one extension family at a time while keeping research
  lifecycle, source identity, provenance, rationale, and disclosure boundaries
  intact.
- The existing plugin registry remains useful for discovery without becoming a
  dynamic execution mechanism.
- Search, review, extraction, writing, and export adapters feed normalized
  service commands, so REST, MCP, audit, validation, and error behavior stay
  consistent.
- Extension selection is replayable because runs record ids and versions rather
  than depending on whatever plugin/template/provider happens to be latest.

### Negative

- Implementations must define small typed contracts instead of quickly pushing
  provider/template-specific maps through `metadata`.
- Dynamic plugin rows remain metadata-only until trusted backend code exists for
  the adapter family, which limits "install a plugin and execute it" behavior.
- Adding API-visible extension categories requires enum and OpenAPI/MCP mirror
  updates under ADR-034.

### Risks

- If `RegisteredPlugin.metadata` becomes the place where lifecycle or source
  semantics live, validation and audit will drift from the research services.
- If adapters persist directly, provider-specific behavior can bypass stage
  gates, provenance, actor capture, and idempotency.
- If prompt text or skill prose is treated as the extension contract, the repo
  will have no enforceable backend boundary for N010.
- If output renderers grow a parallel document model, research final outputs
  will drift from existing document export and disclosure/accountability
  surfaces.

## Non-Goals

- No implementation of new entities, migrations, controllers, DTOs, MCP tools,
  frontend views, providers, parsers, renderers, templates, or graph
  contributors in this ADR.
- No dynamic code loading, plugin marketplace, dependency resolver, provider
  credential store, or sandboxing model.
- No new authentication model, actor override mechanism, error envelope,
  logging stack, enum-mirror system, policy runner, or workflow engine.
- No storage decision for full text, PDFs, manuscripts, raw provider payloads,
  prompts, completions, or large charting datasets.
- No change to ADR-055's citation MCP server name or skill-side phase workflow.

## Related Requirements

- `GC-RSCH-N010` - extensibility.
- `GC-RSCH-N009` - interoperability.
- `GC-RSCH-N011` - observability.
- `GC-RSCH-R004` - provenance chain.
- `GC-RSCH-F003` - stage gating.

## Related Issues

- #1004 - Research REST and MCP tool surface.
- #1021 - Research full-text evidence Q&A adapter.
- #1029 - Research adapter/plugin boundary.
- #1030 - Research local/offline execution mode.

## Related ADRs

- ADR-023 - Plugin Architecture.
- ADR-026 - REST API Access Control.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-035 - MCP Tool Catalog Curation.
- ADR-045 - Evidence Derivation and Temporal State History.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-056 - Research Project Type and Intake Metadata.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-065 - Research Run Observability Snapshot.
- ADR-069 - Research Artifact Provenance Ledger.
- ADR-071 - Research Interoperability and Source Identity Boundary.
- ADR-072 - Research REST and MCP Tool Surface.
