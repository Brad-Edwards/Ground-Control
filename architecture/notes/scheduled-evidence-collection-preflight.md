# Scheduled Evidence Collection Preflight

Issue: #214
Requirement: GC-S005

This is architecture guardrail guidance for scheduled evidence collection. It
is not an implementation plan.

## Boundary

GC-S005 adds orchestration around existing evidence collection and evidence
artifact concepts. Keep these concepts separate:

- **Campaign definition**: project-scoped schedule configuration: frequency,
  enabled/paused state, adapter/family scope, non-secret collection settings,
  and retention policy. It is not an evidence artifact and must not contain raw
  provider credentials.
- **Campaign run**: one attempted execution for a campaign/time window. It owns
  status, timing, sanitized errors, counters, and produced artifact IDs. It is
  not the durable compliance conclusion by itself.
- **EvidenceArtifact**: the append-only summarized evidence record from
  ADR-045. Scheduled collection must persist results through
  `EvidenceArtifactService`; adapters and schedulers must not write artifacts
  directly.
- **EvidenceCollectionAdapter**: provider-facing collection port. It returns
  normalized `EvidenceCollectionResult` values and artifact commands; it does
  not own persistence, scheduling, or retention.
- **Controls and frameworks**: controls are existing `Control` /
  `ScopedControlImplementation` / `RiskControlMapping` endpoints. Framework
  context already exists through control packs, control-pack entry framework
  mappings, methodology profiles, and audit framework links. Do not introduce a
  generic `Framework` aggregate unless a separate lifecycle/query requirement
  justifies it.

Retention for scheduled collection is not Envers audit retention. The existing
`AuditRetentionJob` only purges mutation-history rows. Campaign retention must
be explicit product state for campaign definitions/runs/artifact visibility or
expiry. Deleting or mutating `EvidenceArtifact` rows would change ADR-045's
append-only evidence contract and needs an explicit decision before coding.

## Incumbents To Reuse

- Evidence collection port contracts:
  `EvidenceCollectionAdapter`, `EvidenceCollectionRequest`,
  `EvidenceConnectionConfig`, `EvidenceCollectionScope`,
  `EvidenceCollectionOutputSchema`, `EvidenceCollectionResult`,
  `EvidenceCollectionError`, `EvidenceCollectionRateLimit`, and
  `EvidenceCollectionAdapterRegistry`.
- Existing adapter-family specifications: IAM, cloud infrastructure, and
  CMDB/asset-management families use stable provider keys, canonical
  `scopeType` / `schemaId` values, descriptor capabilities, and versioned output
  schemas. Campaign scope should select those data values; it must not define a
  second evidence-type taxonomy.
- Evidence persistence:
  `CreateEvidenceArtifactCommand`, `EvidenceArtifactService`,
  `EvidenceArtifactRepository`, `EvidenceSourceKind`, `EvidenceType`, and
  `EvidenceArtifactGraphProjectionContributor`.
- Linking and framework surfaces:
  `ControlLinkService` with `ControlLinkTargetType.EVIDENCE`,
  `RiskControlMapping` / `MappingEvidenceRef.evidenceArtifactId`,
  `ControlPack` / `ControlPackEntry.frameworkMappings`, `MethodologyProfile`,
  and `AuditLinkTargetType.FRAMEWORK`.
- Scheduling precedent:
  `ScheduledSweepRunner` and `AuditRetentionJob` keep the scheduled trigger in
  infrastructure and delegate real work to domain services. Follow that
  direction: the scheduler triggers due work; domain services own campaign and
  run invariants.
- Persistence pattern: project-scoped JPA aggregates, Flyway migrations plus
  `_audit` tables where history matters, repository queries scoped by project,
  service-owned transaction boundaries, and explicit database uniqueness/check
  constraints for concurrency-sensitive invariants.
- Cross-cutting layers: `ProjectService`, Bean Validation, domain command
  records, `DomainValidationException` / `ConflictException` /
  `NotFoundException`, `GlobalExceptionHandler`, `ErrorResponse`,
  `ActorFilter` / `ActorHolder`, SLF4J structured logging, and
  `@ConfigurationProperties` with startup validation.

## Cross-Cutting Layers

- **Auth surface**: any REST trigger or campaign write route stays under
  `/api/v1/**` and goes through the existing bearer/browser security chains.
  Because campaigns dereference credential refs and can cause provider-side
  collection, write/trigger routes should be explicit in `ApiPathMatrix`; reads
  can follow the existing project-scoped evidence read model unless they expose
  sensitive provider detail.
- **Secret-handling surface**: `EvidenceConnectionConfig.credentialRef` is the
  credential boundary. Campaign definitions, run records, options, criteria,
  artifact summaries, error details, MCP args, logs, temp files, and process
  argv must never contain raw API tokens, OAuth material, cloud keys, provider
  passwords, signed URLs, authorization headers, or session cookies.
- **Configuration binding**: scheduler enablement, poll cadence, run limits,
  retry caps, timeout bounds, page-size caps, and retention defaults belong in
  `@ConfigurationProperties` with validation. Per-campaign frequency is product
  data; do not create one Spring cron property or one `@Scheduled` method per
  campaign.
- **Request and semantic validation**: DTOs own shape, enum, size, and
  collection bounds. Services own project membership, adapter existence,
  provider/family capability checks, frequency validity, time-window ordering,
  scope option allowlists, same-project control/framework links, and retention
  compatibility with append-only artifacts.
- **Error envelope**: API failures use existing domain exceptions and
  `ErrorResponse`. Provider/run failures recorded as run state or
  `EvidenceCollectionError` must be sanitized and bounded. Do not return raw
  provider response bodies, stack traces, headers, query strings, secrets, raw
  evidence payloads, or full asset/user/device lists in error envelopes.
- **Observability**: logs should use low-cardinality event names and safe IDs:
  project, campaign id, run id, adapter name/version, frequency, status,
  artifact count, error count, duration, and next scheduled time. Do not log
  raw request bodies, provider payloads, source identifiers when they can carry
  sensitive data, or credentials.
- **Concurrency and idempotency**: a due-campaign runner must be safe under
  scheduler overlap, retry, and future multi-node deployment. Use a
  service/database-owned claim or unique run-window invariant so the same
  campaign window cannot produce duplicate artifacts accidentally.
- **Graph and read models**: evidence enters the mixed graph through
  `EvidenceArtifact` and existing link contributors. New campaign/run nodes are
  justified only if users need to traverse or query campaign executions as
  first-class graph participants.

## Extensibility Seams

The stable seams should be data, not controller branches:

- frequency as a bounded domain enum (`DAILY`, `WEEKLY`, `MONTHLY`,
  `QUARTERLY`) with persisted next/due timing;
- adapter and provider selection through `PluginDescriptor` name/capabilities
  and `EvidenceCollectionAdapterRegistry`;
- evidence family through canonical `EvidenceCollectionScope.scopeType` and
  output schema id/version;
- collection scope through bounded criteria/options, not arbitrary provider API
  proxies;
- retention through a named policy/duration/expiry field on campaign/run
  product state, with artifact retention semantics explicit;
- produced evidence through append-only `EvidenceArtifact` creation and
  existing control/risk/audit link surfaces.

Adding another provider, another evidence family, a manual "run now" trigger,
or a narrower control/framework scope should require new data values and
validation, not a new scheduler, exception hierarchy, credential store, or
evidence persistence path.

## Gotchas And Anti-Patterns

- Do not implement scheduling by embedding collection logic inside an
  `@Scheduled` method. The scheduled component should trigger due work and let
  a service own the aggregate and transaction rules.
- Do not bypass `EvidenceArtifactService`, write `EvidenceArtifactRepository`
  directly, or create a second evidence-artifact service.
- Do not create a second adapter registry, provider taxonomy, evidence type
  enum, validation layer, error envelope, audit actor source, credential store,
  or workflow engine.
- Do not use `AuditRetentionJob` to satisfy campaign retention; that job is
  audit-history cleanup, not product evidence retention.
- Do not delete or mutate evidence artifacts for retention without first
  updating the append-only evidence decision in ADR-045.
- Do not treat a campaign run as proof that a control is effective. It is a
  collection event; control assurance still belongs in control tests,
  effectiveness assessments, risk/control mappings, or derived evidence
  artifacts with explicit source references.
- Do not silently narrow a campaign because an adapter lacks a requested
  capability or provider permission. Surface partial/rate-limited/failed status
  with sanitized limitations.
- Do not store raw provider exports, full evidence payloads, PII-heavy lists,
  secrets, or headers in campaign rows, artifact summaries, errors, graph
  properties, logs, or MCP return text.
- Do not invent a generic framework model unless control packs, methodology
  profiles, and audit framework links cannot express the requirement.

## Non-Goals

- No implementation of GC-S005 behavior in this preflight note.
- No concrete provider collectors, queue/workflow engine, raw evidence blob
  store, credential vault, or external scheduling service.
- No replacement of `EvidenceCollectionAdapter`, `EvidenceArtifact`,
  `EvidenceArtifactService`, `PluginRegistry`, `ControlLinkService`,
  `RiskControlMapping`, `ControlPack`, `MethodologyProfile`, `ApiPathMatrix`,
  `ActorFilter`, or `GlobalExceptionHandler`.
- No change to ADR-026 security, ADR-034 enum/mirror policy, ADR-045
  append-only evidence semantics, or ADR-058 derivation-first GRC boundaries.
