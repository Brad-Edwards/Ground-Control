# Ground Control - Architecture

## Mission

Ground Control is a requirements management system with traceability and graph analysis. It manages requirements, tracks relations, links to external artifacts, and runs graph-based analysis (cycles, orphans, coverage gaps, impact, cross-wave validation). Status-drift analysis follows the contract below when added to the sweep surface.

See [ADR-014](../../architecture/adrs/014-pluggable-verification-architecture.md) for the verification architecture and [ADR-011](../../architecture/adrs/011-requirements-data-model.md) for the requirements data model.

## Stack

### Backend

| Component | Technology |
|-----------|-----------|
| Language | Java 21 (Eclipse Temurin) |
| Framework | Spring Boot 3.4 |
| Build | Gradle (Kotlin DSL) with included wrapper |
| Database | PostgreSQL 16 + Apache AGE (graph queries) |
| ORM | Hibernate 6 + Spring Data JPA |
| Auditing | Hibernate Envers |
| Migrations | Flyway |
| Contracts | JML (verified by OpenJML ESC + Z3) |
| Testing | JUnit 5 + jqwik + ArchUnit + Testcontainers |
| Static analysis | Error Prone, SpotBugs, Checkstyle |
| Formatting | Spotless + Palantir Java Format |
| Coverage | JaCoCo |
| Logging | SLF4J + Logback (JSON in prod, console in dev) |
| API docs | Springdoc-OpenAPI |
| Container | Docker (multi-stage, non-root, JDK 21) |
| Registry | GHCR (`ghcr.io/autarchy-ai/ground-control`) |

See [ADR-013](../../architecture/adrs/013-java-spring-boot-rewrite.md) for the Java migration rationale.

### Frontend

| Component | Technology |
|-----------|-----------|
| Framework | React 19 |
| Language | TypeScript 5 |
| Bundler | Vite 6 |
| Routing | React Router 7 |
| Server state | TanStack Query 5 |
| Styling | Tailwind CSS 4 |
| Components | shadcn/ui (Radix primitives) |
| Graph viz | Cytoscape.js + dagre |
| Linting/Format | Biome |
| Testing | Vitest |
| Deployment | Embedded in Spring Boot static resources |

See [ADR-017](../../architecture/adrs/017-interactive-web-application.md) for the frontend decision rationale.

## Package Structure

```
backend/src/main/java/com/keplerops/groundcontrol/
├── api/                          # REST controllers, DTOs, exception handler
│   ├── requirements/             # RequirementController, request/response records
│   ├── baselines/                # BaselineController, request/response records
│   ├── admin/                    # ImportController, SweepController, AnalysisController, GraphController, EmbeddingController
│   ├── verification/             # VerificationResultController, request/response records
│   ├── plugins/                  # PluginController, request/response records
│   └── GlobalExceptionHandler.java
├── domain/                       # Business logic (Spring-web-free)
│   ├── exception/                # Domain exception hierarchy
│   ├── projects/                 # Project entity, repository, service
│   ├── baselines/                # Baseline entity, repository, service
│   ├── verification/             # VerificationResult entity, VerificationStatus/AssuranceLevel enums, repository, service
│   ├── evidence/                 # EvidenceArtifact aggregate plus evidence collection adapter contracts
│   ├── plugins/                  # Plugin interface, PluginRegistry, RegisteredPlugin entity, PluginType/PluginLifecycleState enums
│   └── requirements/
│       ├── model/                # JPA entities (Requirement, RequirementRelation, TraceabilityLink, RequirementEmbedding, etc.)
│       ├── repository/           # Spring Data JPA repository interfaces
│       ├── service/              # RequirementService, AnalysisService, SimilarityService, EmbeddingService, etc.
│       └── state/                # Enums (Status, RelationType, ArtifactType, LinkType, etc.)
├── infrastructure/               # External adapter implementations
│   ├── age/                      # AgeGraphService (Apache AGE Cypher queries)
│   ├── embedding/                # NoOpEmbeddingProvider, OpenAiEmbeddingProvider, config
│   ├── github/                   # GitHubCliClient (gh CLI adapter)
│   ├── sweep/                    # ScheduledSweepRunner, notifiers
│   └── web/                      # CORS config, SPA routing
├── shared/
│   ├── logging/                  # RequestLoggingFilter (MDC request_id)
│   ├── security/                 # ApiSecurityConfig, BrowserSecurityConfig,
│   │                             # BearerTokenAuthFilter,
│   │                             # IpAllowlistFilter, ApiAuthenticationEntryPoint,
│   │                             # ApiAccessDeniedHandler, SecurityProperties (ADR-026)
│   └── web/                      # ActorFilter (audit identity from SecurityContext)
└── GroundControlApplication.java
```

## Dependency Rule

```
api/ -> domain/ <- infrastructure/
```

- `domain/` has no imports from `api/` or `infrastructure/` and no Spring web imports
- `api/` depends on `domain/` - never imports `infrastructure/`
- `infrastructure/` implements interfaces defined in `domain/`

Enforced at compile time by ArchUnit tests in `ArchitectureTest.java`.

## Configuration

Spring profiles drive environment-specific behavior:

- `application.yml` - base config (datasource, JPA, Flyway, server port, security defaults)
- `application-dev.yml` - local dev (`groundcontrol.security.enabled=false`)
- `application-test.yml` - test overrides (Testcontainers, security disabled)

Environment variables use the `GC_` prefix (for example, `GC_DATABASE_URL`, `GC_SERVER_PORT`). See `.env.example`.

## Request filter chains

Once Spring Security is enabled (production default; `dev`/`test` profiles
opt out), requests pass through two explicit, non-overlapping Spring Security
chains:

```
Bearer request chain (@Order(1), Authorization: Bearer ...)
IpAllowlistFilter           # CIDR check (skipped if allowlist empty)
  → BearerTokenAuthFilter   # token → SecurityContext
    → AuthorizationFilter   # path-matrix / ROLE_USER / ROLE_ADMIN
      → ActorFilter         # populates ActorHolder + MDC actor_id=<principal>
        → controllers

Browser/session chain (@Order(2), every non-bearer request)
IpAllowlistFilter           # same network gate
  → form login / session / CSRF
    → AuthorizationFilter   # same API path matrix for /api/v1/**
      → ActorFilter         # same audit actor projection
        → controllers
```

The shared API authorization matrix lives in `ApiPathMatrix` and is applied by
both `ApiSecurityConfig` (bearer traffic) and `BrowserSecurityConfig`
(session-authenticated browser traffic). The browser chain leaves `/login`,
`/logout`, and required static assets anonymous, but the SPA shell (`/`,
`/index.html`) and SPA client routes require a browser session; unauthenticated
navigation redirects to `/login`, while API-shaped unauthenticated XHRs receive
the standard JSON 401 envelope. Controllers do not perform per-method auth
checks - the one deliberate exception,
`PackRegistryAccessGuard`, is a defense-in-depth bridge that re-derives the
admin principal from the same `SecurityContext` and re-asserts `ROLE_ADMIN`
(see ADR-033 §4). `ActorFilter` runs after the security chain so audit
identity tracks the authenticated principal; it writes the principal to MDC
key `actor_id`, the key `logback-spring.xml`'s production JSON appender
exports (alongside `request_id` / `tenant_id`). See [ADR-033](../../architecture/adrs/033-authenticated-audit-actor-provenance.md).

## What Exists vs. What Doesn't

### Exists

**Domain entities:** Requirement, RequirementRelation, TraceabilityLink, GitHubIssueSync, RequirementImport - all JPA with Envers auditing.

**Services:** RequirementService (9 methods), TraceabilityService (forward and reverse artifact lookup), ImportService (StrictDoc parser + idempotent import), GitHubIssueSyncService (CLI-based GitHub sync), AnalysisService (cycle/orphan/coverage/impact/cross-wave; status drift belongs here as read-only analysis), AgeGraphService (Apache AGE graph materialization + Cypher queries).

**Requirement UID allocation (ADR-060, issues #532, #1052):** `RequirementUidAllocator` assigns the next free `{PREFIX}-{N}` UID atomically per project via `pg_advisory_xact_lock`, reading the current high-water mark from `findMaxUidSuffix` (archived rows included, so no suffix is ever recycled). `TraceabilityService.findByArtifact` accepts an optional `projectId` to scope the reverse lookup to a single project; this prevents cross-project issue-number collisions from returning or flagging another project's `GITHUB_ISSUE` links.

**API:** RequirementController (9 REST endpoints), AnalysisController (5 endpoints), ImportController, SyncController, GraphController. GlobalExceptionHandler maps domain exceptions to HTTP error envelopes.

**Audit read surface:** Envers revision data is exposed read-only through `/requirements/{id}/history` (per-revision requirement snapshots with field-level `changes` diffs), `/requirements/{id}/timeline` (unified requirement / relation / traceability-link timeline), and `/requirements/{id}/diff` (two-revision comparison). Diffs use a single `oldValue`/`newValue` vocabulary (`FieldChange` → `FieldChangeResponse`); ADD revisions render as `(null, value)` and DEL revisions as `(value, null)`, so status transitions and traceability-link create/delete appear as discrete diffed events. Large string values are truncated at the API response mapper (`AuditDiffTruncation`, 200-character preview, `truncated` flag) by default, with `?expand=true` returning full values. No new JPA aggregate, Envers table, or migration.

**Read-side workspace endpoints (GC-Q010):** `GET /api/v1/threat-models/workspace` assembles scoped operational assets, boundaries, active flows, threat model entries (with linked controls, requirements, and evidence-freshness staleness indicators) as a read-only composition over existing aggregates. Optional query parameters: `assetId`, `stride` (`StrideCategory` enum), `status` (`ThreatModelStatus` enum), `asOf` (ISO 8601 instant), `freshnessWindowDays` (default 90, positive). Staleness per entry is the worst dominant evidence-freshness state across linked assets, computed by `EvidenceFreshnessAnalysisService.assetScopedEvidenceFreshness` (same substrate as the vendor-risk view). No new JPA aggregate or migration.

**Read-side workspace endpoints (GC-Q009):** `GET /api/v1/risk-scenarios/workspace` assembles risk scenarios with their linked operational assets, controls, findings, evidence, requirements, risk assessments, treatment plans, and risk register memberships as a read-only composition over existing aggregates. Optional query parameters: `assetId`, `status` (`RiskScenarioStatus` enum), `methodologyProfileId`, `approvalState` (`RiskAssessmentApprovalStatus` enum), `treatmentStatus` (`TreatmentPlanStatus` enum), `asOf` (ISO 8601 instant), `freshnessWindowDays` (default 90, positive), `compare` (comma-separated UUIDs, max 10). Review indicator uses explicit signals only: `reassessmentRequiredAt` (highest severity) > register `nextReviewAt` > evidence freshness dominant state (never `updatedAt` or Envers history). No new JPA aggregate or migration.

**Read-side workspace endpoints (GC-Q011):** `GET /api/v1/controls/workspace` assembles control catalog entries with scoped implementations, control tests, evidence summaries sourced from tests and effectiveness assessments, effectiveness ratings, linked findings/exceptions, risk-control mappings, and computed owner queue reasons as a read-only composition over existing aggregates. Optional query parameters: `status` (`ControlStatus` enum), `controlFunction` (`ControlFunction` enum), `owner` (case-insensitive substring), `queue` (`OWNER_MISSING`, `STATUS_DRAFT`, `TEST_EVIDENCE_MISSING`, `ASSESSMENT_MISSING`, `OPEN_EXCEPTION`, `EFFECTIVENESS_WEAK`, `CURRENT`), `asOf` (ISO 8601 instant), and `freshnessWindowDays` (default 90, positive). The workspace emits bounded summaries and links only; raw evidence payloads and a second assurance-state aggregate are intentionally absent. No new JPA aggregate or migration.

**Read-side workspace endpoints (GC-Q012):** `GET /api/v1/evidence-state/workspace` assembles evidence artifacts, observations, evidence freshness, provenance source refs, affected assets, linked controls, downstream risk assessments, and linked findings as a read-only composition over existing aggregates. Optional query parameters: `assetId`, `controlId`, `asOf` (ISO 8601 instant), `freshnessWindowDays` (default 90, positive), and `includeSuperseded` (default false). Freshness is delegated to `EvidenceFreshnessAnalysisService`; the workspace adds bounded summaries and links only, avoiding raw evidence payloads, storage paths, and cross-project traversal. No new JPA aggregate or migration.

**Portfolio reporting view (GC-Q013):** The SPA route `p/:projectId/portfolio` composes the existing read-only GRC workspaces and list endpoints into portfolio summaries for risk posture, control health, evidence freshness, finding trends, asset criticality concentration, and FAIR/NIST/ISO methodology coverage. It is a frontend read-model composition over canonical project-scoped data, preserves UIDs and graph node identifiers for drill-down into the graph/workspace surfaces, and introduces no new persistence, backend reporting aggregate, or methodology engine.

**Threat-control mapping (GC-H006):** `RiskControlMapping` accepts `ThreatModel` as a third analysis-side endpoint, generalizing the exactly one invariant from `(risk_scenario_id XOR risk_register_record_id)` to `(threat_model_id XOR risk_scenario_id XOR risk_register_record_id)`. Enforced at the DB level via CHECK constraint `ck_rcm_analysis_side` (V137) and in the service layer via `RiskControlMappingService.validateExactlyOneAnalysisEndpoint`. Unique constraints `uq_rcm_control_threat_asset` and `uq_rcm_scoped_threat_asset` prevent duplicate mappings. Three new read-only endpoints under `GET /api/v1/analysis/risk-control/`: `unmapped-threats`, `threat-unmapped-controls`, and `threats-insufficient-effectiveness` (freshness + effectiveness bar, configurable via `minEffectiveness`/`asOf`/`freshnessWindowDays`). Graph projection contributor emits `MAPS_THREAT_MODEL` edges. V137 adds `threat_model_id` to `risk_control_mapping` and its audit shadow table.

**GRC analysis endpoints (GC-L007 / GC-T014 / GC-T011):** `GrcAnalysisController` exposes read-only methodology-attributed analysis over `RiskAssessmentResult` rows. `GET /api/v1/analysis/grc/nist-sp-800-30` (GC-T014) returns a NIST SP 800-30 Rev. 1 view: decodes inputs into threat source, threat event, vulnerability, likelihood, and impact bands; derives overall likelihood and risk level as ordinal bands per NIST Tables G-5 and I-2. `GET /api/v1/analysis/grc/fair-quantitative` (GC-T011) returns an Open FAIR quantitative view aligned to O-RT 3.0.1 / O-RA 2.0.1: derives Threat Event Frequency when needed (TEF = CF × PoA), Loss Event Frequency (LEF = TEF × Vulnerability), Loss Magnitude (expected LM = PLM + SLEF × SLM), and Annualized Loss Expectancy (ALE = LEF × LM) via three-point estimates with optional persisted Monte Carlo percentiles; persisted `computedOutputs` take precedence over derived values; emits explicit `limitations` when factor lineage is incomplete or a full distribution calculation is not performed. Both endpoints accept `project`, `asOf`, `riskAssessmentResultId`, and `riskScenarioId` query parameters. No new JPA aggregate or migration.

**Risk appetite & tolerance (GC-T005):** `RiskAppetiteProfile` is a project-scoped, versioned `Service+Aggregate` (`domain/riskappetite`), distinct from `MethodologyProfile`, which stays methodology *vocabulary*. A profile pairs a qualitative appetite statement with a list of methodology-appropriate `ToleranceThreshold` ceilings (one of a quantitative ceiling with units/currency, or an ordinal ceiling with an ordered scale), expressed in a single `MethodologyFamily`. Identity is `(project, appetiteKey, version)`; each version carries an explicit business effective window (`effectiveFrom`/`effectiveTo`) with service-enforced non-overlap among `ACTIVE` versions, so "appetite in force as of date X" is a first-class query rather than an Envers reconstruction. CRUD lives at `/api/v1/risk-appetite-profiles` (writes ROLE_ADMIN via `ApiPathMatrix`, reads ROLE_USER). `GET /api/v1/analysis/grc/appetite-evaluation` (on `GrcAnalysisController`, delegating to a read-only `RiskAppetiteEvaluationService`) compares residual metrics from `RiskAssessmentResult.computedOutputs` against the profile's ceilings and flags breaches for escalation; it never mutates risk data and reports currency/unit/scale mismatches or non-derivable metrics as `limitations`. Migrations `V140`/`V141` add the table and its Envers shadow.

**FAIR materiality extension boundary (GC-T016):** FAIR loss taxonomy and materiality support stays on the existing methodology-attributed risk-assessment lane. `RiskScenario` remains the originating scenario/scoping aggregate, `RiskAssessmentResult` remains the assessment/result aggregate carrying `riskScenarioId`, and `MethodologyProfile.inputSchema` / `outputSchema` remain the profile-scoped vocabulary extension point for FAIR loss forms and optional materiality fields. Primary loss, secondary loss, stakeholder-specific secondary effects, monetary ranges, percentiles, and materiality summaries must stay explicitly method-attributed in the FAIR input/output/result vocabulary; crosswalk entries may classify those fields but must not collapse them into a generic risk score or rewrite method-specific payloads. The FAIR analysis service remains read-only and owns the single FAIR derivation/invariant pass: optional forms-of-loss materiality absence is a `limitations` concern, while malformed three-point ranges, probability bounds, or currency conflicts must not be silently used in LM/ALE arithmetic. A separate materiality aggregate, controller, migration, workflow tool, or side-effect channel is only justified by an independent lifecycle/query/indexing requirement, not by additional FAIR vocabulary alone. As shipped, `FairQuantitativeAnalysisService` decomposes the optional `forms_of_loss` input into a typed `Materiality` view on `Outputs.materiality`, grounded in the authoritative FAIR standard: `FairFormOfLoss` is the six forms of loss defined by The Open Group Risk Taxonomy (O-RT v3.0.1) - Productivity, Response, Replacement, Fines and Judgments, Competitive Advantage, Reputation. It drives the `formsOfLoss` breakdown + `formsOfLossTotal` and classifies the `secondaryLossByStakeholder` entries. The view is descriptive only and never feeds the LEF/LM/ALE arithmetic; the FAIR_V3_0 profile schema (migration `V139`, layered on the V138 O-RT/O-RA source alignment) documents the new input/output vocabulary. FAIR-MAM and FAIR-CAM (the FAIR Institute's separately published models) were removed from the Open FAIR profile in V138 and are intentionally out of scope. The change rides the existing `FairQuantitativeAnalysisResponse` envelope - endpoint signature and the MCP `analyzeFairQuantitative` helper are unchanged.

**Research run lifecycle & stage gating (GC-RSCH-R001 / GC-RSCH-R003 / GC-RSCH-F004, ADR-064 / ADR-065 / ADR-066):** `ResearchRun` is a project-scoped execution aggregate (`domain/research`) that tracks a single research effort through a closed eight-stage lifecycle (`ResearchRunStage`: methodology selection → protocol planning → source search → screening → charting → synthesis → argument construction → prose drafting), kept deliberately separate from run *status* (`ResearchRunStatus`: IN_PROGRESS / BLOCKED / STOPPED / FAILED / COMPLETED). Stage advancement is governed by a service-owned prerequisite matrix (each stage names the predecessor artifact it requires) and by run-scoped human gates (`ResearchRunGate` at five `ResearchGatePoint`s); whether a gate requires a human, auto-accepts, or is disabled is resolved from the run's autonomy level (`ResearchGateBehavior`), so the same lifecycle runs supervised or autonomous without code changes. Gate decision history records the question, recommendation, rationale, decision, actor, and timestamp as persisted research state; workspace `decisions.md` is only a local mirror/export, and recommendation provenance stays separate from human decision provenance. Stage outputs are recorded as `ResearchRunArtifact` manifest rows that are the checkpoint authority: idempotent on an optional key and *superseded* rather than mutated on rework, so a stopped or failed run resumes from its last completed stage without duplicating work, and gate reopening follows artifact supersession. The aggregate stores only bounded, low-cardinality execution state (stage, status, autonomy, budgets, observed token/cost usage, source counts, last-error class) and never prompts, manuscripts, or workspace file paths; manuscript content stays in the workspace, not the record. CRUD + lifecycle live at `/api/v1/research-runs` (start, list, get, advance, record artifact, gate decision, stop, fail, resume, complete, record usage), with a bounded `GET /{id}/snapshot` observability read (current stage, pending gates, artifact readiness, source counts, cost, last error) composed only from persisted state; cross-project access is concealed as `404`. The path is allowlisted for `gc_query` MCP reads. The aggregate is `@Audited` (Hibernate Envers); migrations `V144` through `V149` add the three tables and their audit shadows. Orchestration, curated MCP writes, and a frontend surface are explicit ADR non-goals for this slice.

**Frontend:** React 19 / TypeScript SPA served as embedded static resources from the Spring Boot JAR. Views: Dashboard (project health metrics), GRC Portfolio (`p/:projectId/portfolio`, GC-Q013), Requirements Explorer (browse/filter/author), Requirement Detail (fields, relations, traceability, audit), Dependency Graph (Cytoscape.js DAG visualization), Control and Assurance Workspace (`p/:projectId/control-assurance`, GC-Q011), Evidence and State Explorer (`p/:projectId/evidence-state`, GC-Q012), Threat Modeling Workspace (`p/:projectId/threat-modeling`, GC-Q010), Risk Scenario Workspace (`p/:projectId/risk-scenarios`, GC-Q009). See [ADR-017](../../architecture/adrs/017-interactive-web-application.md).

**Tooling:** Status state machine with JML contracts (verified by OpenJML ESC + Z3), Flyway migrations, Spotless/Error Prone/SpotBugs/Checkstyle/JaCoCo, ArchUnit architecture tests, CI pipeline (build + test + integration + verify), production Dockerfile, GHCR publishing, E2E integration tests.

## Mixed-Entity Graph Participants

The mixed-entity graph (materialized via `AgeGraphService` + Apache AGE) now includes the following first-class domain participants, each backed by a `GraphProjectionContributor` that emits typed nodes into the project-scoped graph: Requirement, OperationalAsset (and Observation), RiskScenario, Control, ControlTest, ControlEffectivenessAssessment, VerificationResult, ThreatModel, Finding, EvidenceArtifact, ArchitectureModelElement, Audit, RiskControlMapping, Document (added GC-G007), and the research provenance trio `RESEARCH_RUN` / `RESEARCH_ARTIFACT` / `RESEARCH_PROVENANCE_NODE` (added ADR-070, #1003). The research participants project the existing relational research ledger (`ResearchRun`, `ResearchRunArtifact`, `ResearchProvenanceNode`/`Edge`) read-only: `ACTIVE` rows of the current reproducibility chain only (`FAILED` runs and superseded rows are excluded from the default projection), provenance edges preserve the ADR-069 upstream→downstream direction via `ProvenanceEdgeRelation`, and only bounded identifiers/enums/hashes/counts are projected, never raw research content. GitHub Issues, external code references, and other artifacts without a backend aggregate remain external targets addressed by identifier only, not first-class graph nodes. Every first-class participant exposes a stable `graphNodeId` field on its REST response (via `GraphIds.nodeId`) for client-side graph navigation. Property keys emitted by contributors must be registered in `AgeGraphService.APPROVED_PROPERTY_KEYS` (ADR-032), and each new contributor must ship a regression test asserting that registration. `ThreatModelLinkTargetType.ARCHITECTURE_MODEL` resolves to an internal `ARCHITECTURE_MODEL_ELEMENT` graph node; data flows are persisted as elements and projected as `DATA_FLOW` edges between the referenced source and target elements in the latest snapshot.

## Mixed-Entity Graph Operations

The four public operations on the mixed-entity graph (GC-G008) are:

- `GET /api/v1/graph/visualization` - returns the full project-scoped graph as a flat node+edge list.
- `POST /api/v1/graph/subgraph/query` - extracts a subgraph anchored at caller-supplied root node IDs.
- `POST /api/v1/graph/traversal/query` - BFS neighborhood traversal with configurable depth and optional entity-type filter.
- `POST /api/v1/graph/paths/query` - shortest-path queries between two node IDs.

**Routing:** `GraphController` → `MixedGraphService` → `MixedGraphClient` → `AgeGraphService` (with JPA-projection fallback when Apache AGE is unavailable, per ADR-032). The JPA fallback builds the same `GraphProjection` shape from JPA aggregates so callers receive a consistent response regardless of AGE availability.

**Node IDs** follow the form `GraphEntityType:UUID` (for example, `CONTROL:a1b2c3d4-…`), produced by `GraphIds.nodeId`. All four endpoints accept node IDs in this format; IDs are validated against the resolved project's projection (not globally), so cross-project references are always rejected.

**Project-scope enforcement:** every operation resolves a single project (via the `project` query parameter) before the graph projection is built. Caller-supplied node IDs are validated only after that projection is materialized, ensuring no cross-project data leaks through traversal.

**Traversal bounds:** `GraphTraversalLimits` is the canonical bound policy covering:
- Maximum root node count per request.
- Maximum BFS depth cap.
- Projection node and edge caps (guards against pathologically large graphs).
- Path result count cap (limits `paths/query` result sets).

**Legacy compatibility:** `/api/v1/requirements/graph/**` routes remain available as requirement-only compatibility endpoints. They must not be extended for mixed-entity traversal; all new cross-entity graph operations go through `/api/v1/graph/**`.

## Deterministic Threat Enumeration Boundary (GC-GRC-007)

GC-GRC-007 belongs to the derivation-backed GRC engine, not to free-form LLM
generation. The enumeration step is a pure, deterministic domain evaluation
over an `ArchitectureModelSnapshotView` and a resolved, project-pinned rule
pack. Given the same snapshot, same rule-pack pin, and same engine version, it
must produce byte-stable candidate ordering and candidate identities.

### Engine Design

The enumeration engine lives in `domain/threatenumeration/service/`. `ThreatEnumerationService`
mirrors the `DataClassificationEvaluationService` sibling pattern:

- **Pure static method**: `enumerate(ThreatRulePackDefinition, String snapshotId, String
  modelVersion, List<ThreatCandidateElementView>)` is side-effect free and directly unit-tested.
  The same definition and views always produce an identical candidate list sorted by
  `(elementStableKey, ruleId, strideCategory)`.
- **Read-only service wrappers**: `enumerateLatest` and `enumerateSnapshot` resolve the pack
  definition via `resolvePackDefinition`, load the architecture-model snapshot, project element
  states to `ThreatCandidateElementView`s, and delegate to `enumerate`.

The predicate model (`ThreatRuleMatchPredicate`) is closed: `ALWAYS`, `CROSSES_TRUST_BOUNDARY`,
`SOURCE_IS_EXTERNAL`, `TARGET_IS_EXTERNAL`, `HAS_DATA_CLASSIFICATION`, `HAS_TRUST_BOUNDARY`,
`HAS_METADATA_TAG`. No predicate makes external calls; all are total functions over stored state.

### Rule-Pack Registry Integration

Rule-pack storage and resolution reuse the pack-registry boundary: `PackRegistryService`,
`PackResolver`, and `PackIntegrityVerifier` own version resolution, compatibility,
checksum/signature verification, withdrawal state, and dependencies. A new `THREAT_RULE_PACK`
pack type and `ThreatRulePackTypeHandler` store rules in the `threat_rule_entries` TEXT column
on `pack_registry_entry` (V171). The integrity verifier's canonical payload covers
`threatRuleEntries` deterministically so the checksum/signature protects rule content. There is
no second registry and no repo-local file loader at enumeration time.

Pack writes remain behind the `ROLE_ADMIN` pack-registry gate. Enumeration reads are available
to any authenticated project caller, consistent with the data-classification evaluation posture.

### Candidate Provenance

Candidate threats are not accepted `ThreatModel` records. They are an explainable intermediate:
each `ThreatCandidate` carries the producing rule id, STRIDE category, element stable key,
element kind, and a bounded `matchedFacts` map—references only to persisted architecture-model
state keys (for example, `elementKind`, `predicate`, `trustBoundaryKey`), never raw adapter payloads.
The existing derivation and architecture-model raw-content key filters remain the leakage
boundary for secrets and source text.

### Curation Contract

Downstream curation is a separate step. Curation may create or update DRAFT `ThreatModel`
entries and link affected elements through `ThreatModelLinkTargetType.ARCHITECTURE_MODEL`,
which already resolves to `ARCHITECTURE_MODEL_ELEMENT` graph nodes. The LLM may confirm,
discard with rationale, or augment deterministic candidates; it must not originate first-pass
enumeration candidates. Likelihood, impact, treatment, and control coverage remain on the
risk-scenario, risk-control, and GRC-analysis lanes.

## Status Drift Analysis

Status drift analysis is a read-only requirements-domain analysis. It flags requirements that are still `DRAFT` while independent artifacts suggest implementation or design completion has already landed. It does not create traceability links, transition requirements, or relax the `IMPLEMENTS`-only on `ACTIVE` rule.

The analysis must build on the existing graph contracts:

- Requirements are project-scoped per ADR-016; all status drift queries must resolve a single project, and every evidence signal must be derived from data owned by that project. Never compare UIDs across projects without project context, and never read project- or repo-unscoped caches (for example, the GitHub issue/PR sync tables) from this path - a project-scoped analysis must not surface another project's (or another repo's) artifacts.
- Evidence is link-based: an `IMPLEMENTS` traceability link on a `DRAFT` requirement (the strongest signal - `IMPLEMENTS` to an issue is allowed pre-`ACTIVE` in the GC-O007/#794 shape), a `DOCUMENTS` link to an `ACCEPTED` ADR (`ArchitectureDecisionRecord` + `TraceabilityLink` with `artifactType=ADR`, `linkType=DOCUMENTS`), a non-`IMPLEMENTS` link to a GitHub issue or pull request, or a non-`IMPLEMENTS` link to a code/test/spec/proof artifact. A `DOCUMENTS` link is evidence, not an implementation link.
- Traceability identifiers follow ADR-011 conventions. GitHub issues and pull requests use raw decimal identifiers; ADRs use ADR UIDs; code/test/config evidence uses repo-relative identifiers. Do not add alternate encodings such as `#42`, `owner/repo#42`, `file:...`, or `adr:021`.
- The analysis path must not shell out to `gh` or scan arbitrary filesystem paths; network and process execution belong in the GitHub sync adapter, not the analysis service.

The report contract is derived evidence: each finding carries the DRAFT requirement, strongest signal type, confidence (`HIGH`, `MEDIUM`, `LOW`), and specific evidence artifacts. Sweep/report callers own the confidence threshold, defaulting to `MEDIUM` so `HIGH` and `MEDIUM` findings are shown while `LOW` remains opt-in.

### Does not exist yet

- Interactive login / OIDC flows (the REST API access-control boundary exists
  via ADR-026; browser login UX and external identity-provider integration do
  not)
- Redis integration (Redis is in docker-compose.yml but nothing in the app uses it)
- Multi-tenancy
- Search
- Concrete verifier adapter implementations in `infrastructure/verifiers/` (ADR-014 §6). The `VerifierAdapter` port interface and request/outcome contracts are defined in the domain layer; future work is implementing adapters for each prover (OpenJML, TLA+/TLC, OPA/Rego, Frama-C, manual review).
- Concrete evidence collection adapter implementations. The `EvidenceCollectionAdapter` port interface, request/result contracts, and classpath/dynamic descriptor registry are defined in the domain layer; external-system collectors belong in infrastructure or trusted plugin code.
- Traceability Matrix view (`/traceability`) and Audit Timeline view (`/audit`) in the frontend
- Apache AGE is optional - the app gracefully degrades to JPA-only analysis when AGE is unavailable

### Exists now

- `specs/tla/` for design-level verification artifacts and state-machine specs, aligned with ADR-014
- Verification result storage (VerificationResult entity with eager-loaded target/requirement, enums, CRUD API, MCP tools) - ADR-014 §2 common schema
- Pluggable verifier adapter interface (`VerifierAdapter`, `VerificationRequest`, `VerificationOutcome`) - ADR-014 §6 port contract for multi-tool integration
- Pluggable evidence collection adapter interface (`EvidenceCollectionAdapter`, `EvidenceCollectionRequest`, `EvidenceCollectionResult`) plus `EvidenceCollectionAdapterRegistry` in the evidence service package. This is the GC-S001 port contract for agent-invoked external evidence collection.
- IAM evidence adapter specification (`domain/evidence/collection/iam/`: `IamEvidenceProvider`, `IamEvidenceFamily`, `IamEvidenceSpecification`). This is the GC-S002 normative contract: it specifies - as data over the GC-S001 port, not a new adapter hierarchy - the supported providers (Okta, Azure AD, AWS IAM), the five evidence families (user access reviews, provisioning/deprovisioning events, MFA enrollment, privileged access, dormant accounts) as canonical scope types and versioned `1.0.0` output schemas, and the descriptor capabilities a conforming IAM collector advertises. Concrete provider collectors remain out of scope (see "Does not exist yet"); design rationale in `architecture/notes/iam-evidence-adapter-spec-preflight.md`.
- Cloud infrastructure evidence adapter specification (`domain/evidence/collection/cloud/`: `CloudEvidenceProvider`, `CloudEvidenceFamily`, `CloudEvidenceSpecification`). This is the GC-S003 normative contract: it specifies - as data over the GC-S001 port, not a new adapter hierarchy - the supported providers (AWS, Azure, GCP), the five evidence families (security group configurations, encryption-at-rest status, logging configurations, backup policies, compliance scan results from AWS Config / Azure Policy / GCP Security Command Center) as canonical scope types and versioned `1.0.0` output schemas, and the descriptor capabilities a conforming cloud collector advertises. Concrete provider collectors remain out of scope (see "Does not exist yet"); design rationale in `architecture/notes/cloud-infrastructure-evidence-adapter-spec-preflight.md`.
- Canonical boundary model derivation (GC-GRC-004). `DerivationService` persists versioned boundary-model snapshots from derived `TRUST_BOUNDARY` facts plus declared `grc.boundaries` from `.ground-control.yaml`; each assignable system-model fact is either mapped to one canonical boundary or recorded as a modeling gap. The persisted model separates boundary gaps from `DerivationCaptureLimit` records, and readback is available at `/api/v1/derivations/runs/{id}/boundary-model` plus the `gc_derivation get_boundary_model` action.
- Canonical architecture model aggregate (GC-GRC-005). `ArchitectureModelService` persists project-scoped, versioned DFD snapshots under `domain/architecturemodel`: stable `ArchitectureModelElement` identities are the linkable graph target, while snapshot-local states carry component/process/data-store/external-entity/data-flow/trust-boundary/data-classification semantics plus provenance. `DerivationService` builds architecture-model snapshots from normalized system-model facts after each run; manual write/read/diff is available at `/api/v1/architecture-models/**` and through `gc_architecture_model`.
- Scheduled evidence collection campaigns (GC-S005, ADR-074). `EvidenceCampaignService` owns project-scoped campaigns under `domain/evidence/campaign`: an `@Audited` `EvidenceCampaign` aggregate carries the frequency (`DAILY`/`WEEKLY`/`MONTHLY`/`QUARTERLY`), scope (adapter + scope type/criteria), connection profile, `credentialRef` (indirection key, never a raw secret), target controls, retention horizon, and the `nextRunAt` scheduling cursor; immutable `EvidenceCampaignRun` rows record each execution. The opt-in scheduler (`infrastructure/campaign`, gated by `groundcontrol.evidence.campaign.enabled`) claims each due campaign with an optimistic conditional cursor advance so concurrent ticks cannot double-run a window, invokes the campaign's `EvidenceCollectionAdapter` over the GC-S001 port, stores each result through `EvidenceArtifactService` (ADR-045) linked to the target controls with an `EVIDENCED_BY` `ControlLink`, and prunes finished runs past retention. REST at `/api/v1/evidence-campaigns/**` (the on-demand `trigger` is admin-only) and the `gc_evidence_campaign` MCP tool.
- CMDB/asset-management evidence adapter specification (`domain/evidence/collection/cmdb/`: `CmdbEvidenceProvider`, `CmdbEvidenceFamily`, `CmdbEvidenceSpecification`). This is the GC-S004 normative contract: it specifies - as data over the GC-S001 port, not a new adapter hierarchy - the supported providers (ServiceNow, Snipe-IT, Jamf), the five evidence families (asset inventory, configuration item status, patch levels, software license compliance, end-of-life tracking) as canonical scope types and versioned `1.0.0` output schemas, and the descriptor capabilities a conforming CMDB collector advertises. External CMDB/device records stay separate from `OperationalAsset` (mapping is a deliberate sync behavior through `AssetExternalId`); concrete provider collectors remain out of scope (see "Does not exist yet"); design rationale in `architecture/notes/cmdb-asset-evidence-adapter-spec-preflight.md`.
- Self-referential traceability enforcement - `check_live_policy.mjs` verifies substantive code files have reverse traceability links to requirements (GC-O002), using the `GET /requirements/traceability/by-artifact` reverse lookup endpoint. Lookup errors are tracked separately for debuggability when the endpoint is unavailable.
- Server-side quality gates (`QualityGateService.evaluate`) synced from `tools/ground_control/policy.json`, evaluated in CI (`make policy-live`) and enforced at the `/implement` completion gate via `gc_assert_quality_gates`. Enforced metric types are `COVERAGE` (IMPLEMENTS / TESTS / DOCUMENTS link coverage for ACTIVE requirements), `ORPHAN_COUNT`, and `COMPLETENESS`; a failing gate blocks the run with a `{name, metric_type, threshold, actual}` envelope.
- ADR metadata drift checks (`check_adr_drift.mjs` and `sync_policy.mjs`) use
  `tools/ground_control/common.mjs` to normalize the live ADR title from the
  API's `folder_title` field, keeping repo ADR titles and live Ground Control
  records comparable under the MCP client's response normalization.

## MethodologyProfile Aggregate and Risk Terminology Crosswalk (GC-T012)

`MethodologyProfile` is the aggregate that defines a risk assessment methodology in a project scope. Each profile carries its input/output JSON schemas, an optional treatment-strategy vocabulary, and (from GC-T012) a **profile-scoped crosswalk** list.

### Crosswalk model

A crosswalk entry maps one concrete source field path (within a profile's `inputSchema`, `outputSchema`, or `treatmentStrategyVocabulary`) to one value in the normalized ten-concept vocabulary: `THREAT_SOURCE`, `THREAT_EVENT`, `VULNERABILITY_OR_EXPOSURE`, `ASSET`, `PROCESS_OR_OBJECTIVE`, `CONSEQUENCE_OR_EFFECT`, `CONTROL`, `LIKELIHOOD_OR_FREQUENCY`, `IMPACT_OR_LOSS_MAGNITUDE`, `TREATMENT`.

The crosswalk is **classifier-only**. It is a declarative labeling layer. It does not aggregate cross-methodology risk scores, collapse method-specific fields into a single ambiguous value, or rewrite assessment result payloads. Assessment outputs remain traceable to their originating methodology profile via `methodologyProfileId`/`profileKey`/`family`/`version` on every `RiskAssessmentResult`.

The crosswalk list is persisted as a JSON-typed `TEXT` column (`crosswalk_entries`) on the `methodology_profile` table via `JacksonTextCollectionConverters.CrosswalkEntryListConverter`. The column is audited via `methodology_profile_aud` under Envers/ADR-026 parity. No separate table is used; the crosswalk is small per profile and is always fetched with the profile.

The two supporting enums (`NormalizedConcept`, `CrosswalkVocabularySurface`) are mirrored at the MCP (`lib.js` constant arrays) and frontend (`api.ts` union types + const arrays) boundaries per ADR-034, and enforced by `tools/policy/checks.py::ENUM_CONTRACT_INVENTORY`.

## Knowledge Ingest Engine (repo-local, out of the product model)

Each repository that uses Ground Control can declare an agent-maintained knowledge base under `docs/knowledge/` via the `knowledge` section of its `.ground-control.yaml`. The `gc_remember` MCP tool captures observations into that repo's inbox; a detached ingest subprocess reads the inbox item, decides update-vs-create via codex, writes the wiki page, and commits the change under a per-repo interprocess lock. The engine lives at `mcp/ground-control/knowledge_ingest.js` with a thin CLI entry at `mcp/ground-control/knowledge_ingest_cli.js`.

The knowledge subsystem is deliberately repo-local tooling, not a Spring backend product feature. No REST controller, DTO, JPA entity, migration, or graph node is added by issues #522–#527. See [ADR-025](../../architecture/adrs/025-knowledge-ingest-engine.md) for the decision to co-locate the engine with the MCP server, use codex as the ingest agent, and serialize via `proper-lockfile`. Rollout phasing lives in `docs/notes/agent-knowledge-system-design.md`.
