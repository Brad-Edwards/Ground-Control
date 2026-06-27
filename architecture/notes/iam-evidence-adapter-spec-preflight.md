# IAM Evidence Adapter Specification Preflight

Issue: #211
Requirement: GC-S002

This is architecture guardrail guidance for specifying IAM evidence adapters
for Okta, Azure AD, and AWS IAM. It is not an implementation plan.

## Boundary

GC-S002 is an evidence-adapter interface requirement. The adapter specification
must build on the existing evidence collection port and evidence artifact
aggregate. It should not create a parallel IAM evidence store, a provider
workflow engine, or a second GRC derivation engine.

Keep these concepts separate:

- `EvidenceCollectionAdapter` is the provider-facing collection port. It
  returns normalized collection results and artifact commands; it does not
  persist rows.
- `EvidenceArtifact` is the durable summarized-evidence aggregate. It is
  append-only and source-backed through `EvidenceSourceRef`; provider adapters
  must not write around `EvidenceArtifactService`.
- `Observation` is a time-bounded state fact about an asset. It is not an IAM
  report blob, and its latest/current view is a projection over history.
- `SystemModelFact` and derivation runs are build-time architecture-model facts
  under ADR-058. IAM evidence from Okta, Azure AD, or AWS IAM is runtime/control
  evidence unless a later requirement introduces a normalized identity fact kind.
- Ground Control application users, bearer tokens, `SecurityProperties`, and
  `UserCredentialPolicy` are the product auth surface. They are not the external
  IAM tenant/account population being evidenced.
- IAM provider records contain identity data and sometimes sensitive metadata.
  The adapter spec must define bounded summaries, external references, category
  counts, and source identifiers rather than storing or returning raw dumps.

The five evidence families in scope are:

- user access reviews;
- provisioning and deprovisioning events;
- MFA enrollment status;
- privileged access reports;
- dormant account lists.

## Incumbents To Reuse

- Evidence collection port contracts:
  `EvidenceCollectionAdapter`, `EvidenceCollectionRequest`,
  `EvidenceConnectionConfig`, `EvidenceCollectionScope`,
  `EvidenceCollectionOutputSchema`, `EvidenceCollectionResult`,
  `EvidenceCollectionError`, `EvidenceCollectionRateLimit`, and
  `EvidenceCollectionAdapterRegistry`.
- Plugin registry metadata: `Plugin`, `PluginDescriptor`, `PluginRegistry`, and
  `PluginType.EVIDENCE_COLLECTOR`. Provider support belongs in descriptor
  capabilities/metadata, not in a bespoke registry.
- Evidence persistence: `CreateEvidenceArtifactCommand`,
  `EvidenceArtifactService`, `EvidenceArtifactRepository`, `EvidenceType`, and
  `EvidenceSourceKind.EXTERNAL` for provider-native references such as tenant,
  user, group, role, access-review campaign, or event ids.
- Existing evidence read surfaces: `/api/v1/evidence-artifacts`,
  `/api/v1/evidence-state/workspace`, `gc_evidence`, and `gc_query` for reads.
  Do not add a second evidence read model unless the new contract has a distinct
  lifecycle and query need.
- Cross-cutting security and audit: `ApiPathMatrix`, `IpAllowlistFilter`,
  `BearerTokenAuthFilter`, `ActorFilter`, `ActorHolder`, Envers, and MDC.
- Error and validation: Bean Validation on DTOs, domain record constructors,
  `DomainValidationException`, `NotFoundException`, `ConflictException`,
  `GlobalExceptionHandler`, and `ErrorResponse`.
- Configuration boundary: `@ConfigurationProperties` classes registered by
  `GroundControlApplication`; provider endpoints, tenant ids, timeouts, page
  sizes, and rate-limit defaults belong there or in non-secret scope/settings.
- HTTP/runtime precedent: infrastructure adapters use bounded clients or
  bounded subprocess patterns. Provider API collection should prefer
  `RestClient`/`WebClient` or official SDK clients with timeouts and response
  limits; shelling out to `okta`, `az`, or `aws` CLIs would require a separate
  security decision.
- API/MCP mirror gates: ADR-034 enum and write-tool DTO drift checks,
  `mcp/ground-control/lib.js`, Zod schemas, and adapter tests if a public MCP
  collection surface is added.

## Cross-Cutting Layers

- Auth surface: any REST collection trigger must stay under `/api/v1/**` and
  pass the ADR-026 bearer/browser chains. Because collection dereferences
  credential refs and can expose IAM populations, the implementation must make
  an explicit `ApiPathMatrix` authorization decision before exposing a trigger;
  authenticated-by-default fallthrough is not enough for a privileged collector.
- Secret-handling surface: `EvidenceConnectionConfig.credentialRef` is the only
  credential field in the collection request shape. Raw client secrets, API
  tokens, AWS access keys, refresh tokens, private keys, and session material
  must not appear in `settings`, `options`, provider error detail, logs,
  artifact summaries, external references, MCP tool args, or process argv.
- Env/config binding: provider-specific configuration must bind through
  `@ConfigurationProperties` with startup validation for required non-secret
  fields, endpoint URI shape, timeout/rate-limit bounds, page-size caps, and
  enabled/provider flags. Provider credentials should remain references to a
  secret source, not new entries in `groundcontrol.security.credentials`.
- Request validation: DTOs or command records own shape, provider id, evidence
  category, time window, dormant threshold, page/item limits, tenant/account
  scope, and option keys. Services/adapters own semantic validation such as
  unsupported category, missing provider capability, insufficient provider
  permission, and from/to ordering.
- Provider parser: provider responses must be parsed into a versioned
  `EvidenceCollectionOutputSchema` with a stable `schemaId`/`schemaVersion`.
  Do not pass raw provider JSON through as evidence payloads or typed maps that
  downstream code treats as canonical.
- Error envelope: backend failures must flow through existing domain exceptions
  and `ErrorResponse`. Provider failures inside a collection result should use
  `EvidenceCollectionError`, whose detail sanitizer already strips secret-like
  keys. Error messages may carry stable provider/category codes and retryability,
  but not response bodies, headers, user lists, tokens, or raw event content.
- Logging and observability: use SLF4J with low-cardinality events and stable
  ids. Log provider id, category, project id, status, artifact count, external
  reference count, and rate-limit state when useful. Do not log raw IAM records,
  user attributes, group memberships, privileged role memberships, dormant user
  lists, authorization headers, credentials, or request/response bodies.
- Persistence: adapters must return `CreateEvidenceArtifactCommand` values and
  let `EvidenceArtifactService` enforce project scoping, source validation, UID
  uniqueness, actor provenance, and supersede-once behavior. A new IAM aggregate
  or migration needs an independent lifecycle/query/indexing reason, not just
  provider vocabulary.
- Graph and GRC: evidence should enter the mixed graph through existing
  `EvidenceArtifact` projection and source refs. If IAM evidence later feeds
  threat/control coverage, bridge through evidence artifacts, observations, or a
  deliberately generalized derivation fact; do not add Okta/Azure/AWS-specific
  graph nodes from the collector.
- MCP boundary: public MCP args must stay Zod-validated, snake_case, and routed
  through existing request helpers. Do not add caller-supplied headers, raw
  bearer tokens, absolute provider URLs, generic method/body proxies, or
  collection commands that bypass backend authorization.
- OS-level exposure: no provider secret may be placed in process argv. If a
  future provider path cannot avoid subprocess execution, it needs the same
  bounded execution, timeout, output cap, and secret-safe invocation review as
  the existing derivation adapter pattern.

## Result Contract

The adapter specification should define a provider-neutral result envelope over
provider-specific details:

- Provider identity: stable provider key (`okta`, `azure-ad`, `aws-iam`),
  tenant/account identifier, and adapter name/version.
- Evidence category: one of the GC-S002 families, expressed as data in the
  scope/schema contract rather than one Java interface per category.
- Collection window and freshness: `from`, `to`, collected-at timestamp,
  dormant threshold where applicable, and explicit partial/rate-limited status.
- Provenance: external reference identifiers for source records and campaigns,
  schema id/version, provider API version when known, and collection profile id.
- Summaries: counts and bounded normalized facts sufficient for evidence review.
  Raw IAM exports, full user lists, group memberships, and event bodies should
  stay out of broad list responses unless a later redacted raw-evidence storage
  contract is introduced.
- Limitations: unsupported category/provider, missing permission, provider
  pagination/rate-limit cutoffs, partial tenant/account coverage, and schema
  fields intentionally omitted for privacy.

## Extensibility Seams

The next likely changes are another IAM provider, another evidence family, and
provider-specific fields for the same family. The seam should be:

- provider key and capabilities on `PluginDescriptor`;
- evidence family in `EvidenceCollectionScope.scopeType` or a bounded category
  field;
- non-secret provider scope in `EvidenceCollectionScope.criteria` and
  `EvidenceConnectionConfig.settings`;
- schema evolution via `EvidenceCollectionOutputSchema.schemaId` and
  `schemaVersion`;
- retry/rate-limit behavior through `EvidenceCollectionRateLimit` and
  `EvidenceCollectionStatus`;
- persistence through append-only `EvidenceArtifact` creation/supersession.

Adding Google Workspace, another Entra ID/Azure AD endpoint, AWS Identity
Center, or a new dormant-account rule should require a provider capability,
schema version, and parser addition. It should not require new controller
branches, new exception hierarchies, a new persistence aggregate, or a new MCP
transport model.

## Gotchas And Anti-Patterns

- Do not conflate external IAM users/groups/roles with Ground Control users,
  admins, bearer credentials, or browser sessions.
- Do not build one adapter interface per provider or per evidence category.
  Use the existing collection port with provider/category/schema data.
- Do not persist raw provider exports in `EvidenceArtifact.summary`, `notes`,
  `EvidenceCollectionError.detail`, logs, graph properties, or MCP return text.
- Do not create a second evidence artifact service, evidence source schema,
  validation layer, exception hierarchy, auth filter, credential store, or MCP
  HTTP helper.
- Do not turn partial collection into a successful empty report. Use `PARTIAL`,
  `RATE_LIMITED`, or `FAILED` with sanitized provider errors and explicit
  limitations.
- Do not let provider permissions or missing endpoints silently narrow the
  report. Unsupported or inaccessible categories must be visible in result
  status/errors/limitations.
- Do not use provider CLI commands, shell strings, or token-in-argv flows for
  Okta, Azure AD, or AWS IAM collection.
- Do not treat IAM status alone as control effectiveness. MFA enrollment,
  dormant accounts, and privileged access are evidence inputs; effectiveness
  conclusions still belong in control tests, assessments, risk/control mapping,
  or evidence artifacts with clear derivation metadata.
- Do not add API-visible enums, MCP field lists, or frontend types without
  updating ADR-034 mirrors and policy/contract tests.

## Non-Goals

- No implementation of GC-S002 behavior in this preflight note.
- No concrete Okta, Azure AD, or AWS IAM collector implementation.
- No new raw-evidence blob store, scheduler, queue, workflow engine, provider
  credential vault, or identity inventory aggregate.
- No replacement of `EvidenceCollectionAdapter`, `EvidenceArtifact`,
  `Observation`, `SystemModelFact`, `PluginRegistry`, `ApiPathMatrix`,
  `ActorFilter`, `GlobalExceptionHandler`, or MCP request helpers.
- No change to ADR-026 security, ADR-034 enum/mirror policy, ADR-045
  append-only evidence semantics, or ADR-058 derivation-first GRC boundaries.
