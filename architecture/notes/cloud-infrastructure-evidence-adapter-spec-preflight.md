# Cloud Infrastructure Evidence Adapter Specification Preflight

Issue: #212
Requirement: GC-S003

This is architecture guardrail guidance for specifying cloud infrastructure
evidence adapters for AWS, Azure, and GCP. It is not an implementation plan.

## Boundary

GC-S003 is an evidence-adapter interface requirement. The adapter
specification must build on the existing GC-S001 evidence collection port and
the ADR-045 evidence artifact aggregate. It should not create a parallel cloud
asset inventory, scanner database, workflow engine, credential store, or second
GRC derivation engine.

Keep these concepts separate:

- `EvidenceCollectionAdapter` is the provider-facing collection port. It
  returns normalized collection results and artifact commands; it does not
  persist rows.
- `EvidenceArtifact` is the durable summarized-evidence aggregate. Cloud
  adapters must persist through `EvidenceArtifactService`, not directly through
  repositories or bespoke tables.
- `Observation` is a time-bounded state fact about an existing operational
  asset. It is not a raw cloud-resource dump.
- `SystemModelFact` and derivation runs are build-time architecture-model facts
  under ADR-058. Cloud console/API evidence is runtime/control evidence unless
  a later requirement introduces a normalized cloud architecture fact kind.
- Cloud provider principals, accounts, subscriptions, projects, regions,
  resource identifiers, security rules, backup policies, and scanner findings
  are external evidence subjects. They are not Ground Control application users,
  bearer credentials, browser sessions, controls, findings, or threat models.
- Provider payloads can expose network topology, public ingress, encryption
  posture, storage names, account ids, and scanner details. The spec must define
  bounded summaries, source identifiers, counts, status fields, and limitations
  rather than storing raw provider exports.

The evidence families in scope are:

- network access controls: AWS Security Groups / NACLs, Azure NSGs, and GCP
  firewall rules;
- encryption-at-rest status for storage, database, and managed service
  resources;
- logging configurations for cloud audit, flow, resource, and service logs;
- backup and retention policies;
- compliance scan results from AWS Config, Azure Policy, and GCP Security
  Command Center.

## Incumbents To Reuse

- Evidence collection port contracts:
  `EvidenceCollectionAdapter`, `EvidenceCollectionRequest`,
  `EvidenceConnectionConfig`, `EvidenceCollectionScope`,
  `EvidenceCollectionOutputSchema`, `EvidenceCollectionResult`,
  `EvidenceCollectionError`, `EvidenceCollectionRateLimit`, and
  `EvidenceCollectionAdapterRegistry`.
- The GC-S002 IAM specification pattern:
  provider enum + evidence-family enum + a normative specification class over
  the existing collection port, not a new adapter interface.
- Plugin registry metadata: `Plugin`, `PluginDescriptor`, `PluginRegistry`, and
  `PluginType.EVIDENCE_COLLECTOR`. Provider and family support belongs in
  descriptor capabilities/metadata.
- Evidence persistence: `CreateEvidenceArtifactCommand`,
  `EvidenceArtifactService`, `EvidenceArtifactRepository`, `EvidenceType`, and
  `EvidenceSourceKind.EXTERNAL` for provider-native source refs such as ARNs,
  Azure resource IDs, GCP resource names, policy assignment IDs, Config rule
  evaluations, SCC finding names, or backup policy IDs.
- Asset state: `OperationalAsset`, `AssetExternalId`, `Observation`, and
  `ObservationService` when the implementation has an existing asset and wants
  a time-bounded state fact. Do not invent a cloud-only asset row or current
  state table.
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
  enabled flags, timeouts, page-size caps, regions, subscriptions, projects, and
  rate-limit defaults belong there.
- HTTP/runtime precedent: infrastructure adapters should use bounded provider
  SDK clients or Spring `RestClient`/`WebClient` with timeouts and response
  limits. Shelling out to `aws`, `az`, or `gcloud` requires a separate security
  decision.
- API/MCP mirror gates: ADR-034 enum and write-tool DTO drift checks,
  `mcp/ground-control/lib.js`, Zod schemas, and adapter tests if a public MCP
  collection surface is added.

## Cross-Cutting Layers

- Auth surface: any REST collection trigger must stay under `/api/v1/**` and
  pass the ADR-026 bearer/browser chains. Because cloud collection dereferences
  credentials and can expose infrastructure posture, trigger writes should be an
  explicit `ApiPathMatrix` authorization decision; admin-only is the safe
  default. Summary reads can use existing evidence read authorization unless a
  new endpoint exposes raw provider detail.
- Secret-handling surface: `EvidenceConnectionConfig.credentialRef` is the only
  credential-bearing field. AWS keys, Azure client secrets, GCP service-account
  JSON, STS/session tokens, refresh tokens, private keys, and signed URLs must
  not appear in `settings`, `criteria`, `options`, artifact summaries, notes,
  source identifiers, provider error detail, logs, MCP args, temp files, or
  process argv.
- Env/config binding: provider configuration must bind through
  `@ConfigurationProperties` with validation for enabled/provider flags,
  endpoint URI shape, region/subscription/project allowlists, timeout bounds,
  page-size caps, item limits, and rate-limit defaults. Provider credentials
  remain secret references, not new entries in `groundcontrol.security.credentials`.
- Request validation: DTOs or command records own provider id, account or
  tenant scope, project/subscription id, region/location, evidence family,
  compliance scanner source, resource-type filter, collection window, item
  limit, and option-key allowlist. Services/adapters own semantic validation:
  unsupported family, missing provider capability, insufficient provider
  permission, invalid time ordering, and impossible provider/family combinations.
- Provider parser: provider responses must be reduced into versioned
  `EvidenceCollectionOutputSchema` payloads with stable `schemaId` and
  `schemaVersion`. Do not pass raw AWS Config, Azure Policy, SCC, security-rule,
  backup, or encryption JSON through as canonical evidence payloads.
- Error envelope: backend failures flow through existing domain exceptions and
  `ErrorResponse`. Provider failures inside a collection result use
  `EvidenceCollectionError`, with sanitized detail and stable provider/family
  codes. Do not echo response bodies, headers, resource policy documents,
  security-rule descriptions, or credential material.
- Logging and observability: use SLF4J with low-cardinality fields: provider,
  family, project, account/subscription/project key, region count, status,
  artifact count, external reference count, rate-limit state, and duration. Do
  not log raw provider records, resource names when avoidable, ingress CIDRs,
  policy documents, backup selections, encryption keys, user tags, headers, or
  request/response bodies.
- Persistence: adapters return `CreateEvidenceArtifactCommand` values and let
  `EvidenceArtifactService` enforce project scoping, source validation, UID
  uniqueness, actor provenance, and supersession. A new cloud aggregate or
  migration needs an independent lifecycle/query/indexing reason.
- Graph and GRC: cloud evidence enters the graph through existing
  `EvidenceArtifact`, `Observation`, asset external-id, and source-ref paths.
  Provider compliance results are evidence inputs; they are not automatically
  Ground Control findings, controls, risk scenarios, or threat-model entries.
- MCP boundary: public MCP args must stay Zod-validated, snake_case, and routed
  through existing request helpers. Do not add caller-supplied headers, raw
  cloud tokens, arbitrary provider URLs, generic method/body proxies, or
  collection commands that bypass backend authorization.
- OS-level exposure: no cloud secret may be placed in process argv. If a future
  provider path cannot avoid subprocess execution, it needs bounded execution,
  timeout, output-cap, temp-file, and secret-safe invocation review before the
  adapter spec can rely on it.

## Result Contract

The adapter specification should define a provider-neutral result envelope over
provider-specific details:

- Provider identity: stable provider key (`aws`, `azure`, `gcp`), account /
  subscription / project identifier, region/location scope, adapter name, and
  adapter version.
- Evidence family: one of the GC-S003 families, expressed through
  `EvidenceCollectionScope.scopeType` and `EvidenceCollectionOutputSchema`, not
  one Java interface per family.
- Collection window and freshness: collected-at timestamp, optional from/to
  window, scanner evaluation timestamp, and explicit partial/rate-limited
  status.
- Provenance: provider-native resource ids, policy/rule ids, scanner source,
  provider API version when known, schema id/version, and collection profile id.
- Summaries: normalized counts and bounded facts sufficient for evidence
  review, such as public ingress count, unrestricted rule count,
  encrypted/unencrypted resource counts, logging enabled/disabled counts,
  backup policy coverage counts, compliance pass/fail counts, and representative
  external references. Raw provider exports stay out.
- Limitations: unsupported service/resource type, missing permission, regional
  coverage gaps, disabled provider APIs, scanner not enabled, pagination or
  rate-limit cutoff, stale scanner data, and intentionally omitted sensitive
  fields.

## Extensibility Seams

The next likely changes are another cloud provider, another resource family,
provider-specific fields for an existing family, and a new compliance scanner.
The seam should be:

- provider key and capabilities on `PluginDescriptor`;
- evidence family in `EvidenceCollectionScope.scopeType` and schema id;
- scanner source as data for the compliance-result family, not a separate
  adapter type;
- account/subscription/project, region/location, resource-type, and
  service-scope filters in `EvidenceCollectionScope.criteria` and non-secret
  connection settings;
- schema evolution via `EvidenceCollectionOutputSchema.schemaId` and
  `schemaVersion`;
- retry/rate-limit behavior through `EvidenceCollectionRateLimit` and
  `EvidenceCollectionStatus`;
- persistence through append-only `EvidenceArtifact` creation/supersession.

Adding OCI, Kubernetes cloud-controller evidence, another AWS service, another
Azure Policy initiative, another SCC finding class, or a provider-specific
encryption field should require provider/family capability data, a schema
version, and parser coverage. It should not require new controller branches,
new exception hierarchies, a new persistence aggregate, or a new MCP transport
model.

## Gotchas And Anti-Patterns

- Do not conflate cloud infrastructure evidence with ADR-058 build-time
  repository derivation. Runtime provider collection is evidence collection
  unless a later requirement adds normalized cloud architecture facts.
- Do not treat provider compliance scan results as Ground Control findings,
  controls, risk scenarios, or threat models without an explicit mapping
  service and lifecycle.
- Do not treat security group/firewall posture alone as control effectiveness.
  It is evidence input; effectiveness conclusions belong in control tests,
  assessments, risk/control mappings, or derived evidence artifacts.
- Do not build one adapter interface per provider or per evidence family.
- Do not persist raw provider exports in `EvidenceArtifact.summary`, `notes`,
  `EvidenceCollectionError.detail`, logs, graph properties, or MCP return text.
- Do not create a second evidence artifact service, source-reference schema,
  validation layer, exception hierarchy, auth filter, credential store, HTTP
  helper, or scanner workflow.
- Do not turn partial collection into a successful empty report. Use `PARTIAL`,
  `RATE_LIMITED`, or `FAILED` with sanitized provider errors and explicit
  limitations.
- Do not let missing permissions, disabled services, unenabled scanners, or
  regional scope gaps silently narrow the report.
- Do not use cloud CLIs, shell strings, token-in-argv flows, or generic
  provider API proxy endpoints for collection.
- Do not add API-visible enums, MCP field lists, or frontend types without
  updating ADR-034 mirrors and policy/contract tests.

## Non-Goals

- No implementation of GC-S003 behavior in this preflight note.
- No concrete AWS, Azure, or GCP collector implementation.
- No new raw-evidence blob store, scheduler, queue, workflow engine, provider
  credential vault, cloud asset inventory aggregate, or scanner-result table.
- No replacement of `EvidenceCollectionAdapter`, `EvidenceArtifact`,
  `Observation`, `SystemModelFact`, `PluginRegistry`, `ApiPathMatrix`,
  `ActorFilter`, `GlobalExceptionHandler`, or MCP request helpers.
- No change to ADR-026 security, ADR-034 enum/mirror policy, ADR-045
  append-only evidence semantics, or ADR-058 derivation-first GRC boundaries.
