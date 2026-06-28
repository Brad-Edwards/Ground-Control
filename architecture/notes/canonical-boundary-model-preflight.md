# Canonical Boundary Model Preflight

Issue: #1117
Requirement: GC-GRC-004

This is architecture guardrail guidance for the canonical boundary model. It is
not an implementation plan.

## Boundary

GC-GRC-004 is part of the ADR-058 derivation-backed GRC engine. The
architectural boundary is the modeling unit used by later architecture-model,
threat, control, coverage, and drift work. The implementation must not turn
boundaries into a loose adapter payload field or a workflow verdict.

Keep these concepts separate:

- `SystemModelFact` is the adapter output substrate. `TRUST_BOUNDARY` facts are
  inputs to boundary modeling; they are not the canonical boundary set.
- Declared boundaries in the repository GRC config are operator-supplied inputs.
  They are not a second boundary schema and must not bypass validation.
- The canonical boundary set is the merged, versioned model output over derived
  and declared inputs.
- Component and flow assignments are coverage assertions over model elements.
  Every derived component and data flow must resolve to a boundary or produce a
  modeling gap.
- `DerivationCaptureLimit` records adapter coverage limits. An unassignable
  component or flow is a modeling gap, not a successful empty derivation and not
  merely an adapter capture limit.
- `OperationalAsset`, `AssetType.BOUNDARY`, and `AssetRelationType.TRUST_BOUNDARY`
  remain operational asset-topology concepts. They can be linked from the
  architecture model later, but they are not the canonical architecture boundary
  set for GC-GRC-004.
- C4 or architecture-model files tracked through traceability remain external
  artifacts unless a separate requirement gives them graph-native lifecycle
  semantics. Do not use file traceability as the boundary store.

If GC-GRC-004 lands before the full GC-GRC-005 architecture-model aggregate, the
boundary persistence shape must still be explicitly versioned and compatible
with that aggregate. Avoid a standalone boundary lifecycle that cannot be
folded into the architecture model without migration churn.

## Incumbents To Reuse

- Derivation substrate:
  `DerivationService`, `DerivationAdapterRegistry`, `DerivationAdapter`,
  `DerivationAdapterDescriptor`, `DerivationRun`, `SystemModelFact`,
  `DerivationCaptureLimit`, `SystemModelFactKind`, `CaptureLimitReason`, and
  the existing CodeQL and IaC/pipeline adapters.
- GRC program contract: ADR-058's `derive -> enumerate -> judge -> enforce ->
  maintain` engine, with `impact_set`, `gap_set`, and `stale_set` as downstream
  consumers of the boundary set.
- Repository config contract: ADR-027 and `gc_get_repo_ground_control_context`
  are the `.ground-control.yaml` schema boundary. Extend the existing strict
  MCP parser and tests for a GRC boundary-declaration block rather than adding
  ad hoc YAML parsing in adapters, services, or agent prose.
- Backend cross-cutting concerns: `ProjectService` for project resolution,
  Bean Validation DTOs, service-layer semantic validation,
  `DomainValidationException` / `NotFoundException` / `ConflictException`,
  `GlobalExceptionHandler` and `ErrorResponse`, `ActorFilter` / `ActorHolder`,
  SLF4J structured logging, Flyway, Envers, and the ArchUnit
  `api -> domain <- infrastructure` rule.
- API/MCP mirrors: `DerivationController`, `gc_derivation`, `mcp/ground-control/lib.js`,
  OpenAPI/MCP contract checks, and ADR-034 enum/DTO mirror rules if new public
  enum values or write-tool fields are introduced.
- Testing patterns: domain service tests for merge and assignment semantics,
  adapter/normalizer tests for derived boundary facts, and `@WebMvcTest` slices
  for any controller surface that exposes boundary sets or modeling gaps.

## Data Contract Guardrails

The canonical boundary set should carry stable, versioned identity:

- project id and identifier;
- architecture-model version or a temporary boundary-set version designed to
  become the architecture-model version;
- commit SHA and derivation run ids used as inputs;
- declared-config provenance, including a schema version and stable digest of
  the declaration block;
- one stable key per boundary, independent of display label changes;
- boundary source classification (`derived`, `declared`, or merged equivalent);
- merge provenance for each canonical boundary;
- assignment records from each derived component and flow to exactly one primary
  boundary, or a structured modeling-gap record when no assignment is possible.

Declared and derived boundaries must merge deterministically. Conflicting
declarations, duplicate keys, overlapping selectors with incompatible meaning,
and derived-vs-declared collisions must surface as validation errors or
modeling gaps with stable reason codes. They must not be silently resolved by
last-write-wins ordering.

Declared-only operation is required. A repository with no derivable language or
surface support must still produce a versioned boundary set from valid
declarations. Unsupported derivation coverage remains visible through capture
limits or downstream gap sets; it must not erase the declared boundary set.

## Cross-Cutting Layers

- Auth surface: any REST boundary-model endpoint stays under `/api/v1/**`.
  Derivation endpoints are already an explicit authenticated-tier decision; if
  canonical boundary reads expose richer topology than raw facts, make a fresh
  `ApiPathMatrix` decision and pin it with security tests.
- Project scoping: all reads, writes, and reverse lookups must resolve through
  `ProjectService` and repository `projectId` predicates. A boundary key is
  never globally unique without the project and version.
- Config validation: declared boundaries from `.ground-control.yaml` need strict
  unknown-key rejection, schema versioning, bounded string/list sizes,
  repo-relative path handling, and containment checks for any path selectors.
  Do not add secrets or credentials to the GRC declaration block.
- Secret handling: boundary facts and declarations may reveal topology but must
  not store source bodies, raw diffs, command output, environment values,
  tokens, provider credentials, or secret values. The existing blocked-payload
  key filter is defense-in-depth, not the primary sanitizer.
- Parser boundary: use structured parsing for declarations and adapter outputs.
  Do not infer canonical boundaries from filenames alone when facts or
  declarations are available.
- OS-level exposure: declaration parsing should be in-process. If a derivation
  adapter invokes a tool, keep the existing argv-based `ProcessBuilder` pattern,
  fixed working directory, timeouts, output caps, sanitized errors, and no
  secret-rich argv/env.
- Error envelope: validation and conflict failures must throw through the
  existing domain exception hierarchy and `GlobalExceptionHandler`. Do not add a
  boundary-specific HTTP error shape.
- Logging and observability: log low-cardinality counts and ids: project,
  version, run ids, boundary count, assignment count, modeling-gap count,
  capture-limit count, and duration. Do not log raw payloads, declarations,
  source snippets, or secret references.
- Persistence and audit: if persistence is needed, follow the existing
  `Service+Aggregate` pattern with `BaseEntity`, Flyway, Envers, repositories
  owned by the domain service, and append/versioned writes. Controllers must not
  write repositories directly.
- Workflow records: GC-GRC-004 does not post GitHub comments or phase markers.
  ADR-029/057/058 issue-thread records stay MCP-rendered workflow artifacts that
  consume boundary results later.

## Extensibility Seams

The next likely changes are additional derivation adapters, richer selector
types for declarations, architecture-model versioning from GC-GRC-005,
boundary-drift comparison, and rule-pack coverage over boundary crossings.

Keep seams data-driven:

- adapter descriptor `languages`, `surfaces`, and `factKinds`;
- declaration schema version and selector type;
- canonical boundary-set schema version;
- merge policy and conflict/gap reason codes;
- assignment strategy from component/flow facts to boundaries;
- provenance fields for tool/ruleset/config versions;
- project-scoped version identifiers used by drift and `impact_set` /
  `gap_set` / `stale_set` computation.

Do not hardcode this repository's `api/domain/infrastructure/mcp/frontend`
boundary names as universal categories. They are valid declared or derived
boundaries for this repo, not the platform's ontology.

## Gotchas And Anti-Patterns

- Do not conflate `TRUST_BOUNDARY` facts with the canonical boundary set.
- Do not conflate canonical architecture boundaries with asset boundaries or
  asset topology relations.
- Do not create parallel declared-boundary schemas in backend, MCP, and docs.
  One `.ground-control.yaml` schema extension should feed all consumers.
- Do not treat missing adapters, empty facts, or unassigned elements as a pass.
- Do not store boundary assignment only in free-form JSON payloads if later
  drift, coverage, or reverse lookup needs to query it.
- Do not add new public enums or MCP fields without updating Java, OpenAPI,
  MCP Zod/constant mirrors, frontend mirrors if exposed, and policy/contract
  tests.
- Do not make merge order depend on YAML order unless the schema explicitly
  defines order as meaningful.
- Do not use `no_baseline` or `not_security_relevant` as boundary-model states.
  Those are workflow screening concepts, and ADR-058 narrows them in the target
  contract.

## Non-Goals

- No implementation of GC-GRC-004 behavior in this note.
- No implementation of GC-GRC-005's full architecture-model aggregate.
- No threat enumeration, control selection, attack-path analysis, risk scoring,
  or screening-record v2 renderer.
- No rewrite of existing derivation adapters, asset topology, threat models,
  risk scenarios, controls, evidence artifacts, or traceability links.
- No runtime DAST, cloud inventory, provider credential collection, deployment
  health checking, or issue-thread posting.
