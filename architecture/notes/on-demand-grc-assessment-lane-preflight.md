# On-Demand GRC Assessment Lane Preflight

Issue: #1129
Requirement: GC-GRC-016

This note records architecture guardrails for the standalone, arbitrary-scope
GRC assessment lane. It is not an implementation plan.

## Boundary

GC-GRC-016 is a second entry point into the derivation-backed GRC engine, not a
second GRC engine. The lane may choose broader scopes and may run outside
`/implement`, but model semantics stay shared with the in-loop gate:
derivation, architecture-model snapshots, threat enumeration, control
identification, coverage, stale/drift computation, and graph reconciliation.

Keep the concepts separate:

- an assessment lane run is workflow evidence about what was requested,
  partitioned, derived, reviewed, and committed;
- derived facts and architecture-model snapshots are the system model;
- candidate threats and controls are deterministic proposals until confirmed
  through existing threat/control/risk-control write paths;
- review disposition is a human gate over proposed graph effects, not a
  mutation of the derivation/enumeration result;
- workflow telemetry is reporting, not the authoritative GRC run or graph state.

## Architecture Decisions

- Reuse the ADR-058 engine contract. `model`, `reassess`, and `re-screen`
  should differ by orchestration and graph-effect policy, not by separate
  derivation or enumeration semantics.
- Add one lane-level command surface only if the current MCP/REST composition
  cannot express the review/commit gate safely. That surface should be a
  discriminator-based `gc_grc_assess` command over fixed backend endpoints, not
  a generic API, Cypher, file-scan, or shell tool.
- Persist or publish lane-run records as schema-versioned records with stable
  inputs, partitions, provenance, review state, dispositions, graph-effect ids,
  and merge/dedup summaries. Do not persist raw diffs, raw scanner output, raw
  source content, prompts, or secrets.
- Partition large scopes by canonical boundary first, then package/path/asset
  where a boundary is unavailable. Merge by stable graph identity and pack
  provenance, with deterministic ordering and idempotent graph writes.
- Review before commit is mandatory unless the project configuration explicitly
  disables the human gate. The config seam must be a validated
  `@ConfigurationProperties` / repo-context shape, not skill prose.

## Incumbents To Reuse

- `DerivationService` and `gc_derivation`: scope normalization, commit/path
  validation, declared-boundary loading, adapter routing, capture limits,
  blocked raw-payload keys, `ActorHolder` audit capture, and architecture-model
  snapshot creation.
- `ArchitectureModelService` and `gc_architecture_model`: stable model element
  keys, snapshot versioning, metadata key blocking, snapshot diffing, and
  project-scoped reads.
- `ThreatEnumerationService` and `gc_threat_enumeration`: deterministic
  rule-pack candidate generation over architecture-model snapshots.
- `ControlIdentificationService` and `gc_control_identification`: deterministic
  control candidates, control gaps, installed-pack eligibility, and coverage
  reads. Confirmations stay in `ControlMappingConfirmationService` and existing
  risk-control/threat-link write paths.
- Existing threat model, risk scenario, control, risk-control mapping,
  evidence, asset, data-classification, and graph services own their aggregates;
  the lane should orchestrate them rather than introducing duplicate records.
- MCP trust-boundary helpers in `mcp/ground-control/lib.js`: Zod schemas,
  `reqArg`, `pick`, REST request helpers, marker parsing/rendering,
  reserved-marker rejection, sensitive-content filtering, body-size caps,
  issue-thread posting, and phase markers.
- Workflow telemetry may record reporting metadata, but it must not become the
  canonical store for GRC assessment outputs.

## Cross-Cutting Layers

- MCP input validation: validate scope selector, mode, project, pack selectors,
  partition limits, review intent, and commit intent with Zod. Do not accept
  caller-supplied derived facts, candidates, scores, graph effects, or final
  merge results as authoritative.
- Backend validation: route all semantic writes through existing controllers
  and services so Bean Validation, `ProjectService`, transaction boundaries,
  domain exceptions, pack resolution, link validation, and repository-level
  project scoping remain authoritative.
- Auth surface: keep routes under `/api/v1/**` and `ApiPathMatrix`; any
  cross-project or gate-override operation needs an explicit admin/configured
  authorization decision. Do not add unauthenticated assessment endpoints.
- Secret and OS exposure: do not put tokens, raw evidence, raw source, raw tool
  output, or generated issue bodies in process argv, logs, telemetry, graph
  metadata, or returned errors. GitHub writes remain MCP-owned and filtered.
- Error envelope: backend refusals should throw `GroundControlException`
  subclasses and serialize through `GlobalExceptionHandler` / `ErrorResponse`.
  MCP refusals should preserve structured `{ok:false,error,message,next_action}`
  envelopes without stack traces or raw response bodies.
- Logging and observability: log low-cardinality event names, run ids,
  partition counts, mode, outcome, and graph-effect counts. Do not log raw
  candidates, assessment inputs, reviewer prose, source snippets, or evidence
  payloads.
- Persistence: any new durable lane aggregate needs `BaseEntity`, project
  scope, Envers parity, Flyway + audit migrations, indexes for primary reads,
  idempotency keys, and graph-projection registration only where the aggregate
  is itself graph-relevant.
- Testing: use pure tests for partition/merge/dedup logic, MCP adapter tests
  for schemas and publish safety, service tests for graph-effect semantics, and
  `@WebMvcTest` slices for new REST shape coverage.

## Extensibility

The main seam is a shared assessment engine invocation parameterized by:

- `mode`: `model`, `reassess`, or `re_screen`;
- `scope`: whole project, path/package set, boundary set, asset set, named
  threat/risk set, or stale/drift set;
- `partitionStrategy`: boundary, package/path, asset, or fixed-size fallback;
- `packSelectors`: threat rule pack, control pack/rule-set, and versions;
- `reviewPolicy`: required, optional, or disabled by validated project config;
- `commitPolicy`: preview only, commit approved graph effects, or dry-run;
- `mergePolicy`: stable identity, dedup keys, conflict disposition, and graph
  reconciliation behavior.

The next obvious changes are scheduling (GC-GRC-017), bootstrap (GC-GRC-018),
and drift workspaces (GC-GRC-019/024). They should add triggers and presentation
around this seam, not re-edit derivation, enumeration, review, or commit logic.

## Gotchas And Anti-Patterns

- Do not fork `/implement` screening logic into a standalone assessment-only
  classifier. The in-loop gate and lane must share one engine.
- Do not treat arbitrary path sets as authoritative architecture boundaries.
  Use boundary snapshots when available and record capture limits when not.
- Do not auto-commit candidate threats/controls from deterministic enumeration.
  Review must approve graph effects before existing write services persist them.
- Do not collapse `model`, `reassess`, and `re-screen` into one generic
  "assessment score." They produce different graph effects and deltas.
- Do not use workflow telemetry as the GRC assessment database.
- Do not expose Cypher, SQL, generic REST passthrough, local file scans, or
  shell-outs through `gc_grc_assess`.
- Do not add prompt-only gates in skills. Review, commit, merge/dedup, and
  override rules must be enforceable in MCP/backend code.
- Do not allow parallel partitions to race on graph writes. Stage partition
  outputs, deterministically merge them, then commit through idempotent service
  calls in one reviewed graph-effect phase.
- Do not silently pass adapter absence, empty baselines, stale links, or pack
  version drift. They are lane outputs requiring review or disposition.

## Non-Goals

- No implementation of #1129 in this note.
- No new threat, control, risk, evidence, asset, or architecture-model schema
  solely for the lane.
- No replacement of ADR-058, Step 3.5 screening, or GC-GRC-012 completion
  coverage enforcement.
- No runtime/DAST/instrumentation scope; ADR-058 keeps this program in the
  build-time repository derivation lane.
- No new privileged agent-side GitHub write path, token surface, local durable
  state directory, or repository-mirrored GRC model by default.
