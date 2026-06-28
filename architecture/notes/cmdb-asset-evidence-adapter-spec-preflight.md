# CMDB And Asset Evidence Adapter Specification Preflight

Issue: #213
Requirement: GC-S004

This is architecture guardrail guidance for specifying CMDB and asset
management evidence adapters for ServiceNow, Snipe-IT, and Jamf. It is not an
implementation plan.

## Boundary

GC-S004 is an evidence-adapter interface requirement. The adapter
specification must build on the GC-S001 evidence collection port, the existing
`EvidenceFamilySpec` pattern from GC-S002 / GC-S003, and the ADR-045 evidence
artifact aggregate. It should not create a parallel asset inventory database,
CMDB sync engine, device-management store, credential store, scheduler, or
second GRC derivation engine.

Keep these concepts separate:

- `EvidenceCollectionAdapter` is the provider-facing collection port. It
  returns normalized collection results and artifact commands; it does not
  persist rows.
- `EvidenceArtifact` is the durable summarized-evidence aggregate. CMDB and
  asset adapters must persist through `EvidenceArtifactService`, not directly
  through repositories or bespoke tables.
- `OperationalAsset` is Ground Control's canonical operational asset. A
  ServiceNow CI, Snipe-IT asset, or Jamf device is an external evidence subject
  unless deliberately mapped through `AssetExternalId` and existing asset
  services.
- `Observation` is a time-bounded state fact about an existing operational
  asset. Patch posture, lifecycle status, device enrollment, and license state
  can become observations only through `ObservationService`; they are not raw
  provider dumps or mutable current-state columns.
- `SystemModelFact` and derivation runs are build-time architecture-model facts
  under ADR-058. Live CMDB/device-management evidence is runtime/control
  evidence unless a later requirement introduces a normalized architecture fact
  kind.
- Patch gaps, unsupported end-of-life assets, and license overages are evidence
  inputs. They are not automatically Ground Control findings, control
  effectiveness conclusions, risk scenarios, threat models, or audit findings.
- Provider payloads can expose hostnames, serial numbers, usernames, device
  ownership, installed software, license keys, IPs, custom fields, lifecycle
  status, and vulnerability-adjacent posture. The spec must define bounded
  summaries, source identifiers, counts, status fields, and limitations rather
  than storing raw provider exports.

The evidence families in scope are:

- asset inventory;
- configuration item status;
- patch levels;
- software license compliance;
- end-of-life tracking.

## Incumbents To Reuse

- Evidence collection port contracts:
  `EvidenceCollectionAdapter`, `EvidenceCollectionRequest`,
  `EvidenceConnectionConfig`, `EvidenceCollectionScope`,
  `EvidenceCollectionOutputSchema`, `EvidenceCollectionResult`,
  `EvidenceCollectionError`, `EvidenceCollectionRateLimit`, and
  `EvidenceCollectionAdapterRegistry`.
- Evidence-family specification helpers: `EvidenceFamilyDescriptor` and
  `EvidenceFamilySpec`, plus the GC-S002 / GC-S003 pattern of provider enum,
  family enum, and normative specification class over the existing collection
  port.
- Plugin registry metadata: `Plugin`, `PluginDescriptor`, `PluginRegistry`, and
  `PluginType.EVIDENCE_COLLECTOR`. Provider and family support belongs in
  descriptor capabilities/metadata.
- Evidence persistence: `CreateEvidenceArtifactCommand`,
  `EvidenceArtifactService`, `EvidenceArtifactRepository`, `EvidenceType`, and
  `EvidenceSourceKind.EXTERNAL` for provider-native source refs such as
  ServiceNow `sys_id` / CI numbers, Snipe-IT asset ids / tags, Jamf computer or
  mobile-device ids, policy ids, software ids, model ids, or lifecycle-source
  references.
- Asset state: `OperationalAsset`, `AssetExternalId`, `Observation`,
  `ObservationService`, `AssetService`, `AssetSubtypeValidator`, and the
  existing asset repositories when the implementation has an existing asset and
  wants to attach external identifiers or time-bounded state facts. Do not
  invent a CMDB-only asset row or current-state table.
- Existing evidence read surfaces: `/api/v1/evidence-artifacts`,
  `/api/v1/evidence-state/workspace`, `gc_evidence`, and `gc_query`. A second
  evidence read model needs a distinct lifecycle/query reason.
- Cross-cutting security and audit: `ApiPathMatrix`, `IpAllowlistFilter`,
  `BearerTokenAuthFilter`, `ActorFilter`, `ActorHolder`, Envers, and MDC.
- Error and validation: Bean Validation on DTOs, domain record constructors,
  `DomainValidationException`, `NotFoundException`, `ConflictException`,
  `GlobalExceptionHandler`, and `ErrorResponse`.
- Configuration boundary: `@ConfigurationProperties` classes registered by
  `GroundControlApplication`; non-secret provider settings, endpoint overrides,
  enabled flags, timeouts, page-size caps, scope allowlists, stale-device
  thresholds, and rate-limit defaults belong there.
- HTTP/runtime precedent: infrastructure adapters should use bounded provider
  SDK clients or Spring `RestClient` / `WebClient` with timeouts and response
  limits. Shelling out to vendor CLIs requires a separate security decision.
- API/MCP mirror gates: ADR-034 enum and write-tool DTO drift checks,
  `mcp/ground-control/lib.js`, Zod schemas, and adapter tests if a public MCP
  collection surface is added.

## Cross-Cutting Layers

- Auth surface: any REST collection trigger must stay under `/api/v1/**` and
  pass the ADR-026 bearer/browser chains. Because CMDB collection dereferences
  credentials and can expose enterprise asset, device, patch, and license
  posture, trigger writes should be an explicit `ApiPathMatrix` authorization
  decision; admin-only is the safe default. Summary reads can use existing
  evidence read authorization unless a new endpoint exposes raw provider detail.
- Secret-handling surface: `EvidenceConnectionConfig.credentialRef` is the only
  credential-bearing field. ServiceNow API credentials, OAuth refresh tokens,
  Jamf API tokens, Snipe-IT tokens, client secrets, session cookies, license
  keys, signed URLs, and private keys must not appear in `settings`, `criteria`,
  `options`, artifact summaries, notes, source identifiers, provider error
  detail, logs, MCP args, temp files, or process argv.
- Env/config binding: provider configuration must bind through
  `@ConfigurationProperties` with validation for enabled/provider flags,
  endpoint URI shape, instance/site allowlists, timeout bounds, page-size caps,
  item limits, stale-check-in thresholds, and rate-limit defaults. Provider
  credentials remain secret references, not new entries in
  `groundcontrol.security.credentials`.
- Request validation: DTOs or command records own provider id, instance/site
  scope, asset class, platform, department/location/smart group filters,
  evidence family, collection window, stale threshold, item limit, and
  option-key allowlist. Services/adapters own semantic validation: unsupported
  family, missing provider capability, insufficient provider permission,
  invalid time ordering, unavailable provider modules, and impossible
  provider/family combinations.
- Provider parser: provider responses must be reduced into versioned
  `EvidenceCollectionOutputSchema` payloads with stable `schemaId` and
  `schemaVersion`. Do not pass raw ServiceNow, Snipe-IT, or Jamf JSON through as
  canonical evidence payloads.
- Error envelope: backend failures flow through existing domain exceptions and
  `ErrorResponse`. Provider failures inside a collection result use
  `EvidenceCollectionError`, with sanitized detail and stable provider/family
  codes. Do not echo response bodies, headers, custom-field values, user lists,
  installed-software lists, license keys, or credential material.
- Logging and observability: use SLF4J with low-cardinality fields: provider,
  family, project, instance/site key, status, asset/device count, artifact count,
  external reference count, rate-limit state, and duration. Do not log raw
  provider records, hostnames when avoidable, usernames, serial-number lists,
  installed software, license details, IP addresses, custom fields, headers, or
  request/response bodies.
- Persistence: adapters return `CreateEvidenceArtifactCommand` values and let
  `EvidenceArtifactService` enforce project scoping, source validation, UID
  uniqueness, actor provenance, and supersession. If an implementation also
  maps records to existing assets, it must use `AssetExternalId`,
  `ObservationService`, and `AssetService`; a new CMDB aggregate or migration
  needs an independent lifecycle/query/indexing reason.
- Graph and GRC: CMDB evidence enters the graph through existing
  `EvidenceArtifact`, `Observation`, asset external-id, and source-ref paths.
  Provider patch, license, status, or EOL results are evidence inputs; they are
  not automatically findings, controls, risk scenarios, or threat-model entries.
- MCP boundary: public MCP args must stay Zod-validated, snake_case, and routed
  through existing request helpers. Do not add caller-supplied headers, raw
  provider tokens, arbitrary provider URLs, generic method/body proxies, or
  collection commands that bypass backend authorization.
- OS-level exposure: no CMDB or device-management secret may be placed in
  process argv. If a future provider path cannot avoid subprocess execution, it
  needs bounded execution, timeout, output-cap, temp-file, and secret-safe
  invocation review before the adapter spec can rely on it.

## Result Contract

The adapter specification should define a provider-neutral result envelope over
provider-specific details:

- Provider identity: stable provider key (`servicenow`, `snipe-it`, `jamf`),
  instance/site identifier, adapter name, and adapter version.
- Evidence family: one of the GC-S004 families, expressed through
  `EvidenceCollectionScope.scopeType` and `EvidenceCollectionOutputSchema`, not
  one Java interface per family.
- Collection window and freshness: collected-at timestamp, optional from/to
  window, provider check-in/evaluation timestamp, stale threshold, and explicit
  partial/rate-limited status.
- Provenance: provider-native object ids, asset tags where non-sensitive,
  CI/device/software/license/policy/model ids, provider API version when known,
  schema id/version, and collection profile id.
- Summaries: normalized counts and bounded facts sufficient for evidence
  review, such as total asset/device count, active/inactive/retired counts,
  managed/unmanaged counts, CI status counts, stale-check-in count,
  current/stale/missing patch counts, baseline reference, compliant/noncompliant
  license counts, over-assigned/under-assigned seat counts, supported/EOL/unknown
  lifecycle counts, and representative external references. Raw exports stay
  out.
- Limitations: unsupported provider family, missing permission, disabled module,
  filtered scope, pagination or rate-limit cutoff, stale provider agent data,
  unavailable license/EOL source, partial site coverage, and intentionally
  omitted sensitive fields.

## Extensibility Seams

The next likely changes are another asset-management provider, another evidence
family, provider-specific fields for an existing family, and optional mapping
from provider records to existing Ground Control assets. The seam should be:

- provider key and capabilities on `PluginDescriptor`;
- evidence family in `EvidenceCollectionScope.scopeType` and schema id;
- instance/site, asset class, platform, department/location/smart group, status,
  model, software, and baseline filters in `EvidenceCollectionScope.criteria`
  and non-secret connection settings;
- schema evolution via `EvidenceCollectionOutputSchema.schemaId` and
  `schemaVersion`;
- retry/rate-limit behavior through `EvidenceCollectionRateLimit` and
  `EvidenceCollectionStatus`;
- source-system mapping through `AssetExternalId` and observation creation only
  when a separate product behavior deliberately maps provider records onto
  existing `OperationalAsset` rows;
- persistence through append-only `EvidenceArtifact` creation/supersession.

Adding Intune, Kandji, Lansweeper, Tanium, another ServiceNow table, another
Jamf device class, or richer software lifecycle data should require
provider/family capability data, a schema version, and parser coverage. It
should not require new controller branches, new exception hierarchies, a new
persistence aggregate, or a new MCP transport model.

## Gotchas And Anti-Patterns

- Do not conflate external CMDB/device records with `OperationalAsset`. Asset
  creation/upsert is a separate sync behavior and must go through existing asset
  services and external-id mapping.
- Do not overwrite asset lifecycle, scope, criticality, ownership, or knowledge
  state based solely on provider CI status, patch level, license compliance, or
  EOL posture.
- Do not treat patch gaps, license overages, or unsupported assets as Ground
  Control findings or control-effectiveness conclusions without an explicit
  mapping service and lifecycle.
- Do not build one adapter interface per provider or per evidence family.
- Do not persist raw provider exports in `EvidenceArtifact.summary`, `notes`,
  `EvidenceCollectionError.detail`, logs, graph properties, or MCP return text.
- Do not create a second evidence artifact service, source-reference schema,
  validation layer, exception hierarchy, auth filter, credential store, HTTP
  helper, asset inventory table, or scanner workflow.
- Do not turn partial collection into a successful empty report. Use `PARTIAL`,
  `RATE_LIMITED`, or `FAILED` with sanitized provider errors and explicit
  limitations.
- Do not let missing permissions, disabled modules, stale agents, or narrowed
  site/scope coverage silently narrow the report.
- Do not use provider CLI commands, shell strings, token-in-argv flows, or
  generic provider API proxy endpoints for collection.
- Do not add API-visible enums, MCP field lists, or frontend types without
  updating ADR-034 mirrors and policy/contract tests.

## Non-Goals

- No implementation of GC-S004 behavior in this preflight note.
- No concrete ServiceNow, Snipe-IT, or Jamf collector implementation.
- No new raw-evidence blob store, scheduler, queue, workflow engine, provider
  credential vault, CMDB sync engine, asset inventory aggregate, patch-result
  table, license ledger, or EOL catalog.
- No replacement of `EvidenceCollectionAdapter`, `EvidenceFamilySpec`,
  `EvidenceArtifact`, `Observation`, `OperationalAsset`, `AssetExternalId`,
  `SystemModelFact`, `PluginRegistry`, `ApiPathMatrix`, `ActorFilter`,
  `GlobalExceptionHandler`, or MCP request helpers.
- No change to ADR-026 security, ADR-034 enum/mirror policy, ADR-043 asset
  subtype boundaries, ADR-045 append-only evidence semantics, or ADR-058
  derivation-first GRC boundaries.
