# Data Classification Lattice Preflight

Issue: #1119
Requirement: GC-GRC-006

This note records architecture guardrails for the project-scoped data
classification lattice. It is not an implementation plan.

## Boundary

GC-GRC-006 extends the server-side architecture model with a versioned
information-flow policy. The lattice is the project policy that says which
data labels may flow to which labeled sinks. It is not an LLM prompt, a
frontend enum, an asset criticality field, or a risk-severity scale.

Keep these concepts separate:

- `ArchitectureModelElementState.dataClassificationKey` is a label assignment
  reference. It is not the label definition, policy graph, or evaluation
  result.
- `ArchitectureModelElementKind.DATA_CLASSIFICATION` can represent derived or
  declared classification hints. It must not become the whole taxonomy/policy
  store by stuffing lattice semantics into an architecture element payload.
- `SystemModelFactKind.DATA_CLASSIFICATION_HINT` is adapter output. It is an
  input to architecture-model state and policy validation, not an authoritative
  project taxonomy by itself.
- The label lattice is not necessarily a linear ordinal enum. The default
  labels (`PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `PII`, `CREDENTIALS`,
  `SECRETS`, `REGULATED`) need stable keys and explicit allowed-flow or
  dominance semantics so custom projects can model incomparable or grouped
  labels without Java enum churn.
- Sensitivity labels are not trust boundaries. Boundary membership can help
  explain a violation, but a trust-boundary crossing is not automatically a
  lattice failure unless the versioned policy says so.
- Operational asset classification, asset subtype, asset criticality, FAIR/NIST
  impact, and data sensitivity are different dimensions. Do not reuse one as a
  surrogate for another.
- Lattice violations are deterministic policy results. They are not
  observations, risk scores, threat-model entries, or agent-authored review
  findings.

The database-side architecture model remains authoritative. Repository GRC
configuration, MCP input, and frontend state can seed or edit policy, but the
evaluated taxonomy, policy, and assignments must be stored server-side with the
model snapshot used for the check.

## Incumbents To Reuse

- Architecture model substrate: `ArchitectureModelService`,
  `ArchitectureModelSnapshot`, `ArchitectureModelElement`,
  `ArchitectureModelElementState`, `ArchitectureModelElementStateCommand`,
  repositories, snapshot diffing, and the existing `dataClassificationKey`
  normalization seam.
- Derivation substrate: `DerivationService`, `DerivationAdapterRegistry`,
  `SystemModelFact`, `DerivedSystemModelFact`, `DerivationFactProvenance`, and
  the existing sensitive-payload key rejection before facts are persisted.
- Project configuration precedent: `MethodologyProfile` style project-scoped
  persisted configuration with JSON-map fields, explicit service validation,
  Flyway, Envers, and project-scoped repository predicates. If GC-GRC-023 uses
  `.ground-control.yaml` as an input, extend the strict MCP context parser and
  persist the normalized result; do not make application services read YAML.
- GRC analysis surface: `GrcAnalysisController`, `GrcAnalysisService`, and
  response-record mapping if policy evaluation is exposed as read-only
  analysis.
- Finding substrate: `Finding`, `FindingService`, `FindingType.POLICY_VIOLATION`,
  `FindingLink`, `GraphTargetResolverService`, and
  `FindingGraphProjectionContributor` if violations are persisted as durable
  findings.
- Graph substrate: `ArchitectureModelGraphProjectionContributor`,
  `GraphEntityType.ARCHITECTURE_MODEL_ELEMENT`, `GraphIds`, `GraphNode`,
  `GraphEdge`, `MixedGraphService`, `GraphTraversalLimits`, and
  `AgeGraphService.APPROVED_PROPERTY_KEYS`.
- Backend cross-cutting concerns: `ProjectService`, Bean Validation request
  DTOs, service-layer semantic validation, `DomainValidationException`,
  `ConflictException`, `NotFoundException`, `GlobalExceptionHandler`,
  `ErrorResponse`, `ActorFilter`, `ActorHolder`, SLF4J, Flyway, Envers, and the
  ArchUnit `api -> domain <- infrastructure` rule.
- Public contract mirrors: ADR-034 enum/API mirror rules, `@WebMvcTest`
  controller slices, MCP action-discriminated tool patterns, `gc_query`
  allowlist discipline, and docs/API sync when a REST shape changes.

## Cross-Cutting Layers

- Auth and authorization: REST routes stay under `/api/v1/**` and pass through
  `IpAllowlistFilter`, bearer/browser security chains, shared `ApiPathMatrix`,
  and `ActorFilter`. Taxonomy or policy writes can suppress or create
  deterministic findings, so they need an explicit `ApiPathMatrix` decision;
  absent a project-role model, prefer admin-only writes and authenticated
  project-scoped reads.
- Project scoping: every taxonomy, policy, assignment, snapshot lookup, flow
  evaluation, finding write, and graph target check must resolve one project
  and use repository predicates that include `projectId`. Label keys are not
  globally unique.
- Request validation: DTO Bean Validation should bound strings, arrays, and
  enums. Services own semantic checks: stable label-key syntax, duplicate keys,
  policy edges referencing known labels, default fallback, assignment
  references to existing labels, flow endpoints in the same snapshot, and
  policy version compatibility with the snapshot being evaluated.
- Lattice validation: do not infer policy from list order. Validate the chosen
  representation as a real information-flow lattice or explicit permitted-flow
  relation: no dangling labels, no ambiguous duplicate aliases, no accidental
  self-contradictions, and a deterministic join/allow decision for every
  evaluated source/sink pair.
- Secret handling: labels may name sensitive classes such as credentials or
  regulated data, but the system must never persist or log secret values,
  source bodies, raw diffs, raw tool output, environment values, bearer tokens,
  provider credentials, or raw PII examples in taxonomy metadata, assignment
  payloads, findings, errors, or graph properties.
- Error envelope: validation, conflict, not-found, auth, and data-integrity
  failures flow through existing domain exceptions and `GlobalExceptionHandler`.
  Error detail may include stable field names, label keys, snapshot ids, and
  reason codes. It must not echo raw model payloads or data samples.
- Logging and observability: log low-cardinality events only: project,
  snapshot/model version, policy version, evaluated flow count, violation
  count, derivation method, and duration. Actor and request context come from
  `ActorFilter` and `RequestLoggingFilter`.
- Persistence and audit: if the policy/taxonomy needs new tables, use the
  existing audited aggregate pattern: `BaseEntity`, project scope, Flyway,
  Envers audit shadows, repository ownership in the domain service, and
  migration smoke coverage. Avoid hiding queryable policy or assignment state
  only inside `metadata` JSON when evaluation, diffing, and stale-set logic need
  it.
- Graph/AGE exposure: architecture nodes already carry
  `dataClassificationKey`. Add graph properties only when traversal needs
  them; keep them bounded and register them in `AgeGraphService` with tests.
  Do not expose policy evaluation through Cypher/SQL passthrough.
- MCP and workflow side effects: MCP may adapt fixed backend endpoints, but the
  backend remains the semantic authority. Do not calculate lattice violations,
  create findings, or validate policy only in MCP, frontend, or agent prose. No
  GitHub comments or workflow markers are part of GC-GRC-006 itself.
- Runtime/OS exposure: this requirement does not need subprocesses, shell-outs,
  `gh`, `curl`, arbitrary file reads, runtime DAST, or dynamic instrumentation.
  Future derivation adapters that invoke tools must keep fixed working
  directories, timeouts, output caps, sanitized errors, and no secret-rich
  argv/env.

## Result And Finding Guardrails

The deterministic result should carry enough evidence for humans and agents to
understand the violation without re-running an LLM:

- analysis kind or derivation method with version;
- project, snapshot/model version, policy version, and evaluation timestamp;
- flow element identity and stable key;
- source and sink element identities/stable keys;
- source label, sink label or sink allowance, and the failed policy relation;
- provenance from the architecture model and derivation facts;
- structured reason code such as `label_flow_not_permitted`;
- limitations for missing labels, unknown policy versions, or unsupported
  element kinds.

If violations become durable `Finding` rows, make them idempotent and
project-scoped. Re-running the same policy against the same model should not
spray duplicate open findings. Use the existing finding aggregate and either
extend `FindingLinkTargetType` / `GraphTargetResolverService` /
`FindingGraphProjectionContributor` to support internal architecture-model
targets or deliberately keep architecture references as external identifiers
until that target contract is widened. Do not write finding rows directly from
controllers or MCP handlers.

## Extensibility

Keep the seam data-driven and versioned:

- taxonomy schema version;
- policy version and derivation method;
- stable label key plus display name/description;
- explicit dominance, join, or permitted-flow relation;
- aliases only as validated metadata, never as identity;
- assignment target kind (`DATA_FLOW`, `DATA_STORE`, `EXTERNAL_ENTITY`,
  architecture element, or operational asset) and target stable key/id;
- evaluation scope (`snapshotId`, latest snapshot, or as-of) and policy version;
- reason codes for violation, missing label, stale policy, and non-derivable
  cases.

The likely next changes are GC-GRC-020 sensitive-flow checks, stale-set
computation when policy changes, frontend editing, and additional derivation
adapters. Those should add policy data, rule handlers, response fields, or
contract mirrors without changing auth, error envelopes, graph identity, or the
architecture-model aggregate boundary.

## Gotchas And Anti-Patterns

- Do not implement the default labels as the only Java enum if per-project
  customization is required.
- Do not treat `PII`, `CREDENTIALS`, `SECRETS`, and `REGULATED` as a simple
  severity order unless the policy explicitly defines that order.
- Do not store policy only in `application.yml`, frontend constants, MCP
  constants, `.ground-control.yaml`, or architecture element metadata.
- Do not let unlabeled sensitive flows pass silently. Missing classification is
  at least a limitation or gap, not a clean result.
- Do not conflate label taxonomy with trust boundaries, asset subtype,
  criticality, risk severity, methodology impact, or evidence freshness.
- Do not add a second graph model or a generic universal graph-edge table for
  data flows; use architecture-model elements and projected flow edges.
- Do not duplicate validation in MCP, frontend, controllers, and services. The
  service layer is the semantic authority.
- Do not return only prose, prompts, or agent judgments for policy violations.
- Do not log or serialize raw examples of PII or secret material to explain a
  classification.

## Non-Goals

- No implementation of GC-GRC-006 behavior in this note.
- No new runtime scanner, DAST lane, cloud inventory collector, or provider
  credential path.
- No replacement for `ArchitectureModelService`, `DerivationService`,
  `FindingService`, graph traversal, asset topology, methodology profiles,
  threat models, risk scenarios, controls, or evidence artifacts.
- No implementation of GC-GRC-020 quantitative sensitive-flow metrics or the
  full ADR-058 completion gate.
- No GitHub issue-thread posting, final-report rendering, or workflow marker
  change.
