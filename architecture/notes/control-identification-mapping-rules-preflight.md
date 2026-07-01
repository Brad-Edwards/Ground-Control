# Control Identification And Mapping Rules Preflight

Requirement: GC-GRC-008
Issue: #1121

This note records architecture guardrails for deterministic control
identification and threat-to-control confirmation. It is not an implementation
plan.

## Boundary

GC-GRC-008 sits after threat enumeration and before confirmed mitigation
coverage. Keep these concepts separate:

- `ThreatCandidate` is a deterministic enumeration output from GC-GRC-007. It
  is not a curated `ThreatModel` row and must not be treated as confirmed threat
  coverage until the threat curation path creates or selects a threat-model
  entry.
- Candidate controls are derived suggestions with rule provenance and
  implementation guidance. They are not `RiskControlMapping` rows, do not imply
  `Control.status`, and must not count as implemented mitigation.
- `ControlPackEntry` and its materialized `Control` are the installed pack
  surface. Use `ControlPackEntry.implementationGuidance`,
  `frameworkMappings`, `entryStatus`, and the owning `ControlPack` version and
  checksum for pack provenance; do not parse raw OSCAL/catalog payloads in the
  mapping engine.
- Existing project controls are first-class `Control` rows. They may be
  candidates even when they are not pack-backed, but their provenance must say
  they came from the project catalog rather than from a pack rule.
- Confirmed mitigation coverage belongs to the canonical relationship
  aggregates: `RiskControlMapping` for coverage and `ThreatModelLink` with
  `targetType=CONTROL` and `linkType=MITIGATED_BY` for threat-owned traversal.
  Do not introduce a parallel `ThreatControlMapping` table or count a generic
  link alone as complete coverage.
- No-match results are explicit control-design gaps with stable reason codes,
  threat category, control objective, and source candidate identity. They are
  normal result data, not exceptions, unless the request itself is invalid.

The mapping path should remain deterministic: threat category -> control
objective -> candidate controls, with stable ordering and bounded provenance.
LLM or agent judgment may confirm or reject candidates later, but it must not be
the source of candidate generation.

## Incumbents To Reuse

- Threat floor: `ThreatEnumerationService`, `ThreatCandidate`,
  `ThreatRuleCategory`, `ThreatRulePackDefinition`, and the pure-static
  evaluation pattern with a service wrapper for project-scoped pack/snapshot
  resolution.
- Control packs: `PackResolver`, `PackIntegrityVerifier`, `ControlPackService`,
  `ControlPackRepository`, `ControlPackEntryRepository`, `ControlPackEntry`,
  `ControlPackLifecycleState`, and `ControlPackEntryStatus`.
- Project controls: `Control`, `ControlRepository`, `ControlFunction`,
  `ControlStatus`, `Control.source`, `Control.category`, and
  `Control.objective`.
- Confirmation: `RiskControlMappingService`,
  `CreateRiskControlMappingCommand`, `MappingControlRole`,
  `ThreatModelLinkService`, `CreateThreatModelLinkCommand`,
  `ThreatModelLinkTargetType.CONTROL`, and
  `ThreatModelLinkType.MITIGATED_BY`.
- Graph/queryability: `RiskControlMappingGraphProjectionContributor`
  (`MAPS_THREAT_MODEL`, `MAPS_CONTROL`, `IN_ASSET_CONTEXT`),
  `ThreatModelGraphProjectionContributor`, `GraphEntityType`, `GraphIds`, and
  `GraphTargetResolverService`.
- Validation/errors/observability: Bean Validation request records,
  service-owned semantic validation, `DomainValidationException`,
  `NotFoundException`, `ConflictException`, `GlobalExceptionHandler`,
  `ErrorResponse`, `ActorFilter` / `ActorHolder`, Envers, and SLF4J structured
  logs.

## Cross-Cutting Layers

- Auth surface: any REST endpoint must stay under `/api/v1/**` and use the
  shared security path matrix, bearer/browser filters, and actor filter. Do not
  add controller-local authorization, actor request fields, or privileged
  GitHub/shell side effects.
- Request parsing and shape validation: DTOs should use Bean Validation and enum
  binding for pack ids, version constraints, snapshot ids, threat model ids, and
  confirmation request shape. Services own same-project checks, duplicate
  confirmation handling, candidate-to-threat consistency, and rule provenance
  completeness.
- Pack trust and integrity: candidate selection must consume installed,
  project-scoped pack entries and verified registry resolution paths. It must
  not bypass `PackResolver`, `PackIntegrityVerifier`, lifecycle state, or active
  entry status by reading registry metadata or OSCAL files directly.
- Error envelope: invalid inputs, missing packs, missing threats, cross-project
  endpoints, and duplicate confirmations must throw existing domain exceptions
  through `GlobalExceptionHandler`. No feature-specific error envelope and no
  raw catalog/threat payloads in error details.
- Secret and payload handling: threats, architecture facts, and controls can be
  sensitive. Logs and graph properties should carry ids, counts, enum values,
  pack versions, rule ids, and reason codes, not raw threat narratives, OSCAL
  bodies, source snippets, credentials, tokens, or environment values.
- Persistence and audit: only confirmed relationships need durable mapping rows.
  If candidate/gap history becomes durable, it must be a project-scoped,
  audited aggregate with Flyway/audit-table parity, not an opaque JSON blob
  hidden on `ThreatModel` or `Control`.
- Graph exposure: controllers/services must not write AGE rows directly.
  Coverage queries should be answered from canonical JPA rows and existing graph
  projection contributors. New graph properties require the existing property
  allowlist and regression tests.
- Config and OS/runtime exposure: this requirement should not require new
  secrets, env vars, shell commands, repository filesystem scans, `gh`, `curl`,
  or network clients. If a later rule-pack source needs operator knobs, use
  `@ConfigurationProperties` with startup validation.
- API/MCP/frontend contracts: API-visible enums, request fields, response
  fields, or MCP tool surfaces must update the Java/OpenAPI tests, MCP
  allowlists/constants, frontend API types when exposed, and docs in the same
  change.

## Extensibility

The required seam is the rule source and provenance, not a new relationship
aggregate. Candidate output should expose a rule-set id/version, threat
category, objective id/key, candidate control id or UID, candidate source
(`control_pack_entry` or `project_control`), pack id/version/checksum when
pack-backed, matched fields, and gap reason when unmatched.

Keep the category-to-objective rules data-driven enough that adding a new
`ThreatRuleCategory`, framework family, control pack, or objective synonym does
not require editing confirmation logic or graph projection. Keep confirmation
idempotent across reruns: repeated confirmation of the same threat/control/asset
context should converge on the same canonical rows or return the existing
relationship, not create divergent link and mapping state.

Boundary-scoped coverage should use existing structured context. If the threat
candidate is tied to an architecture-model element or boundary, preserve that
stable key and model version in candidate provenance. Only set
`RiskControlMapping.operationalAsset` or scoped-control asset context when the
boundary exists as a project-scoped `OperationalAsset`; do not coerce
architecture-model element keys into asset ids.

## Gotchas And Anti-Patterns

- Do not conflate threat categories, STRIDE categories, control objectives,
  `Control.category`, `ControlFunction`, and `MappingControlRole`. They answer
  different questions.
- Do not select controls by unbounded full-text search over descriptions or
  narratives. Match against bounded rule/objective fields and record exactly
  which rule and field selected the candidate.
- Do not silently drop no-match categories or pack gaps. Surface them as
  explicit gap results even when existing project controls are also searched.
- Do not persist candidate suggestions as confirmed mitigations, transition
  controls to implemented/operational, or satisfy coverage gates before human or
  agent confirmation creates canonical rows.
- Do not create duplicate pack schemas, JSON parsers, exception hierarchies,
  audit writers, graph writers, target resolvers, auth filters, logging
  channels, or workflow posting logic.
- Do not make candidate ordering depend on database insertion order. Sort by
  stable category/objective/control identifiers and pack version data.

## Non-Goals

- No implementation of GC-GRC-008 behavior in this note.
- No new control-pack import machinery, OSCAL parser, threat enumeration
  engine, attack-path analysis, risk scoring, control implementation workflow,
  or efficacy-test generation.
- No replacement for `ThreatModel`, `ThreatModelLink`, `Control`,
  `ControlPackEntry`, `RiskControlMapping`, graph projection, or existing GRC
  screening records.
- No direct GitHub issue-thread posting, repository scanning, runtime DAST,
  dynamic instrumentation, or external framework lookup.
