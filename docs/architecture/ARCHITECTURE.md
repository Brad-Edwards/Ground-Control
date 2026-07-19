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

**Threat-control mapping (GC-H006):** `RiskControlMapping` accepts `ThreatModel` as a third analysis-side endpoint, generalizing the exactly one invariant from `(risk_scenario_id XOR risk_register_record_id)` to `(threat_model_id XOR risk_scenario_id XOR risk_register_record_id)`. Enforced at the DB level via CHECK constraint `ck_rcm_analysis_side` (V137) and in the service layer via `RiskControlMappingService.validateExactlyOneAnalysisEndpoint`. Unique constraints `uq_rcm_control_threat_asset` and `uq_rcm_scoped_threat_asset` prevent duplicate mappings. Two read-only endpoints under `GET /api/v1/analysis/risk-control/`: `unmapped-threats` and `threat-unmapped-controls`. (A third, `threats-insufficient-effectiveness`, was published only as an MCP action backed by a REST route that never existed on `RiskControlAnalysisController`; it and its `as_of`/`minEffectiveness`/`freshnessWindowDays` parameters were retired in #1309 as dead, divergent as-of surface—see "As-Of Time Semantics" below.) Graph projection contributor emits `MAPS_THREAT_MODEL` edges. V137 adds `threat_model_id` to `risk_control_mapping` and its audit shadow table.

**Research run lifecycle & stage gating (GC-RSCH-R001 / GC-RSCH-R003 / GC-RSCH-F004, ADR-064 / ADR-065 / ADR-066):** `ResearchRun` is a project-scoped execution aggregate (`domain/research`) that tracks a single research effort through a closed eight-stage lifecycle (`ResearchRunStage`: methodology selection → protocol planning → source search → screening → charting → synthesis → argument construction → prose drafting), kept deliberately separate from run *status* (`ResearchRunStatus`: IN_PROGRESS / BLOCKED / STOPPED / FAILED / COMPLETED). Stage advancement is governed by a service-owned prerequisite matrix (each stage names the predecessor artifact it requires) and by run-scoped human gates (`ResearchRunGate` at five `ResearchGatePoint`s); whether a gate requires a human, auto-accepts, or is disabled is resolved from the run's autonomy level (`ResearchGateBehavior`), so the same lifecycle runs supervised or autonomous without code changes. Gate decision history records the question, recommendation, rationale, decision, actor, and timestamp as persisted research state; workspace `decisions.md` is only a local mirror/export, and recommendation provenance stays separate from human decision provenance. Stage outputs are recorded as `ResearchRunArtifact` manifest rows that are the checkpoint authority: idempotent on an optional key and *superseded* rather than mutated on rework, so a stopped or failed run resumes from its last completed stage without duplicating work, and gate reopening follows artifact supersession. The aggregate stores only bounded, low-cardinality execution state (stage, status, autonomy, budgets, observed token/cost usage, source counts, last-error class) and never prompts, manuscripts, or workspace file paths; manuscript content stays in the workspace, not the record. CRUD + lifecycle live at `/api/v1/research-runs` (start, list, get, advance, record artifact, gate decision, stop, fail, resume, complete, record usage), with a bounded `GET /{id}/snapshot` observability read (current stage, pending gates, artifact readiness, source counts, cost, last error) composed only from persisted state; cross-project access is concealed as `404`. The path is allowlisted for `gc_query` MCP reads. The aggregate is `@Audited` (Hibernate Envers); migrations `V144` through `V149` add the three tables and their audit shadows. Orchestration, curated MCP writes, and a frontend surface are explicit ADR non-goals for this slice.

**Protocol plan (GC-RSCH-F008 / GC-RSCH-F009, ADR-083):** `ProtocolPlanAggregate` (`domain/research/service`) records the structured phase-2 protocol plan behind the run's active `PROTOCOL_PLAN` artifact attempt: a `ProtocolPlanCoverage` row resolves each ADR-080 methodology-requirements-contract `REQUIREMENT`/`OPEN_PROTOCOL_QUESTION` entry to exactly one `ProtocolCoverageDisposition` (FILLED / RESOLVED_BY_USER_DECISION / DEFERRED_NON_BLOCKING / NOT_APPLICABLE_WITH_RATIONALE / BLOCKING_DECISION_REQUIRED), classifying `FILLED` answers by `ProtocolAnswerProvenance`; a `ProtocolPlanSection` row records one method-specific output section per `ProtocolSectionKind` (`ProtocolMethodShape` derives which section kinds the selected method profile requires), with `ProtocolSourceRole` legal only on `SOURCE_ROLES` sections of the `taxonomy_development` method. A plan with any `BLOCKING_DECISION_REQUIRED` coverage disposition blocks the `SOURCE_SEARCH` stage from starting. The method key, method profile version, methodology-requirements-contract id/attempt, and artifact attempt are all resolved server-side from the run's active selection and active artifacts, never client-supplied. CRUD lives at `/api/v1/research-runs/{id}/protocol-plan` (record, get); migrations `V184`-`V187` add the plan, plan-audit, coverage, and section tables. The endpoint is allowlisted for `gc_query` MCP reads.

**Frontend:** React 19 / TypeScript SPA served as embedded static resources from the Spring Boot JAR. Views: Dashboard (project health metrics), Requirements Explorer (browse/filter/author), Requirement Detail (fields, relations, traceability, audit), Dependency Graph (Cytoscape.js DAG visualization). The composed GRC Portfolio, Control and Assurance Workspace, Evidence and State Explorer, Threat Modeling Workspace, and Risk Scenario Workspace views (GC-Q009/Q010/Q011/Q012/Q013) are retired product surface (ADR-089, issue #1346); their underlying lower-level aggregates (`ThreatModel`, `RiskScenario`, `Control`, `ControlTest`, `EvidenceArtifact`, risk-control mapping) remain available through their own CRUD/analysis endpoints, just not through a composed workspace view. The console shell, navigation groups, design-system foundations, authenticated-session UX, and workflow-operations interaction patterns are specified in [Console Information Architecture and Design-System Foundations](../../architecture/design/console-ia-design-system.md), which is the construction reference for GC-Q015 and GC-Q016. See [ADR-017](../../architecture/adrs/017-interactive-web-application.md).

**Contract surface (GC-O014 / ADR-082):** `contracts/` is the committed
contract surface for externally consumed API and workflow shapes. The backend
remains the semantic source: `generateContractOpenApi` captures Springdoc
OpenAPI, `make contracts` refreshes `contracts/openapi/openapi.json` and
`contracts/gen/typescript/api.ts`, and `frontend/src/types/api.ts` is only a
compatibility re-export to the generated artifact. JSON Schemas under
`contracts/schemas/` carry invariant inventories, and
`contracts/authz/path-matrix.yaml` is checked against `ApiPathMatrix.java`.
`make contracts-check` is the local regenerate-and-diff drift gate; CI also
runs the breaking-change check against `contracts/CHANGES.md`.

**Tooling:** Status state machine with JML contracts (verified by OpenJML ESC + Z3), Flyway migrations, Spotless/Error Prone/SpotBugs/Checkstyle/JaCoCo, ArchUnit architecture tests, CLD oracle battery scaffolds for conformance/property/negative/golden/differential tests, CI pipeline (build + test + integration + verify), production Dockerfile, GHCR publishing, E2E integration tests.

## As-Of Time Semantics (ADR-084 §5)

The canonical as-of coordinate for the whole system is the **Envers revision number** (`revinfo.rev`)—a single, already-total order over every audited entity, with actor attribution. There is exactly one resolution rule, owned by `AsOfRevisionResolver` (`domain/audit/service`, backed by `RevisionRepository`). The resolver returns the greatest `rev` whose `revinfo.revtstmp` is at or before the given instant (an inclusive boundary), or `Optional.empty()` when no revision satisfies that condition (including when nothing has ever been audited yet).

The resolver is global, not project-scoped. A revision is a coordinate, not an authorization, so project-scoped filtering stays the responsibility of each consumer. For example, `BaselineService` resolves a revision and then filters requirements by project when reconstructing a snapshot.

Per-service reimplementation of "as of" semantics using a bespoke query against a business timestamp or a second temporal parameter shape is a defect. Issue #1309 retired the one surviving example (a dead MCP action, `threats-insufficient-effectiveness`, calling a REST route that never existed) and deleted seven repository methods that filtered "current" rows by *business* time (`observedAt`, `derivedAt`, `testDate`) rather than system revision time. An OpenAPI structural guard (`OpenApiAsOfParameterGuardTest`) fails the build if any controller or request DTO ever declares an as-of-shaped parameter again.

**Consumers of the spine:**

- **Baselines** (`BaselineService.create`) pin a baseline to `asOfRevisionResolver.currentRevision()`. `Optional.empty()` (nothing audited yet) maps to the persisted origin sentinel `0` at this one boundary—`Baseline.revisionNumber` is a persisted `int` and `getRequirementsAtRevision(0)` is defined to return an empty list; the resolver itself never invents a `0` revision.
- **AGE graph snapshots** (`age_graph_snapshot.source_revision`, added V201) record the revision visible to the snapshot's own `REPEATABLE_READ` publishing transaction: `AgeGraphService.materializeGraph()` resolves it strictly after the publication advisory lock and strictly before `GraphProjectionRegistryService.buildProjection()`, so the resolver query and every contributor read inside the projection observe the same PostgreSQL snapshot. `source_revision` is nullable with **no FK to `revinfo`** and **no backfill**: `AuditRetentionJob` deletes old `revinfo` rows, so a FK would either block that cleanup or silently turn old snapshots into retention pins, and a materialization spans time—the revision visible at publication cannot be reconstructed after the fact from `published_at`. Legacy snapshot rows (published before V201) stay explicitly `NULL`. `source_revision`, `version` (the snapshot lifecycle counter), the revision's own timestamp, and `published_at` (the wall-clock publication instant, `clock_timestamp()` not `now()` so a long build doesn't understate it) are four genuinely distinct clocks—do not conflate them.
- **Every mutable graph contributor's backing entity must be `@Audited`.** A snapshot's `source_revision` claim is only honest if every entity the projection reads is on the Envers spine—an unaudited entity could change graph contents without advancing any revision. `Document` was the one historical exception (closed in #1309: `@Audited` + `document_audit`, V202). ADR-061's workflow reporting model joined the graph in #1311 only after `WorkflowRun` and `WorkflowPhaseEvent` gained audit shadows in V203. A structural guard (`GraphProjectionContributorAuditGuardTest`) fails the build if a future `GraphProjectionContributor` reads an unaudited entity.
- **A stored `source_revision` is a coordinate, not a retention guarantee.** `AuditRetentionJob` purges `revinfo` rows (and their audit-shadow rows) past the configured retention window; an old snapshot's recorded revision can become unreconstructable once its `revinfo` row is gone. The graph itself does not need that history to keep serving reads—only a hypothetical future historical-reconstruction feature would.

**Event-history, not competing spines:** ADR-045's evidence supersession chains and the research provenance ledger (`ResearchProvenanceNode`/`Edge`, ADR-069/070) are unchanged by this—they are event-history semantics layered *on* the Envers spine, not alternative time coordinates. Neither introduces a second "as of" query surface; both remain relational aggregates whose own revisions are, like everything else, addressable through the one resolver if a future feature needs to.

**Non-goal:** there is no historical graph query API yet (`resolveAsOf(Instant) → Optional<Integer>` is the seam a future one would consume) and no conversion of business-effective dates into system revision time.

## Mixed-Entity Graph Participants

The mixed-entity graph (materialized via `AgeGraphService` + Apache AGE) includes the following first-class domain participants, each backed by a `GraphProjectionContributor` that emits typed nodes into the project-scoped graph: Requirement, OperationalAsset (and Observation), RiskScenario, Control, ControlTest, VerificationResult, ThreatModel, Finding, EvidenceArtifact, Audit, RiskControlMapping, ScopedControlImplementation, Document (added GC-G007), the research provenance trio `RESEARCH_RUN` / `RESEARCH_ARTIFACT` / `RESEARCH_PROVENANCE_NODE` (added ADR-070, #1003), and ADR-061 workflow reporting runs (added #1311). The research participants project the existing relational research ledger (`ResearchRun`, `ResearchRunArtifact`, `ResearchProvenanceNode`/`Edge`) read-only: `ACTIVE` rows of the current reproducibility chain only (`FAILED` runs and superseded rows are excluded from the default projection), provenance edges preserve the ADR-069 upstream→downstream direction via `ProvenanceEdgeRelation`, and only bounded identifiers/enums/hashes/counts are projected, never raw research content. Workflow reporting projects an audited `WORKFLOW_RUN` node plus a deduplicated workflow-family `WORK_ITEM_REFERENCE` for complete `(project, repo, issueNumber)` identities. `RUN_FOR_WORK_ITEM` records the stable association and every persisted phase event remains a distinct directed `WORKFLOW_PHASE_EVENT` edge; incomplete identities remain isolated run nodes. It does not revive a workflow executor or first-class work-item aggregate. Requirement traceability is part of the same projection: `IMPLEMENTS`, `TESTS`, `DOCUMENTS`, `CONSTRAINS`, and `VERIFIES` run from a requirement to its artifact endpoint. Live controls and risk scenarios resolve to their canonical graph nodes; GitHub issues, code references, and other artifacts without a live projected aggregate use deduplicated `ARTIFACT_REFERENCE` nodes keyed by an exact, project-qualified identifier tuple. Every first-class participant exposes a stable `graphNodeId` field on its REST response (via `GraphIds.nodeId`) for client-side graph navigation. Property keys emitted by contributors must be registered in `AgeGraphService.APPROVED_PROPERTY_KEYS` (ADR-032), and each new contributor must ship a regression test asserting that registration.

## Mixed-Entity Graph Operations

The four public operations on the mixed-entity graph (GC-G008) are:

- `GET /api/v1/graph/visualization` - returns the full project-scoped graph as a flat node+edge list.
- `POST /api/v1/graph/subgraph/query` - extracts a subgraph anchored at caller-supplied root node IDs.
- `POST /api/v1/graph/traversal/query` - BFS neighborhood traversal with configurable depth and optional entity-type filter.
- `POST /api/v1/graph/paths/query` - shortest-path queries between two node IDs.

**Routing:** `GraphController` → `MixedGraphService` → `MixedGraphClient` → `AgeGraphService` (with JPA-projection fallback when Apache AGE is unavailable, per ADR-032). The JPA fallback builds the same `GraphProjection` shape from JPA aggregates so callers receive a consistent response regardless of AGE availability.

**Node IDs** follow the form `GraphEntityType:identity` (for example, `CONTROL:a1b2c3d4-…`), produced by `GraphIds`. Aggregate-backed nodes use their UUID. `ARTIFACT_REFERENCE` nodes use a bounded SHA-256 digest of length-framed project id, artifact type, and exact persisted identifier components, preventing long identifiers and cross-project collisions. All four endpoints validate IDs against the resolved project's projection (not globally), so cross-project references are always rejected.

**Project-scope enforcement:** every operation resolves a single project (via the `project` query parameter) before the graph projection is built. Caller-supplied node IDs are validated only after that projection is materialized, ensuring no cross-project data leaks through traversal.

**Traversal bounds:** `GraphTraversalLimits` is the canonical bound policy covering:
- Maximum root node count per request.
- Maximum BFS depth cap.
- Projection node and edge caps (guards against pathologically large graphs).
- Path result count cap (limits `paths/query` result sets).

**Legacy compatibility:** `/api/v1/requirements/graph/**` routes remain available as requirement-only compatibility endpoints. They must not be extended for mixed-entity traversal; all new cross-entity graph operations go through `/api/v1/graph/**`.

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
- Research high-risk operation **executors** and sandbox runtime. ADR-086's
  authorization control plane now exists (see "Exists now"), but the executors
  that perform generated-code execution, browser activity, lab/hardware actions,
  and external writes (and the sandbox runtime they run in) are not implemented
  yet. An executor must present an unexpired, matching authorization record and
  run under the declared sandbox profile before performing any effect.
- Traceability Matrix view (`/traceability`) and Audit Timeline view (`/audit`) in the frontend
- Apache AGE is optional - the app gracefully degrades to JPA-only analysis when AGE is unavailable

### Exists now

- `specs/tla/` for design-level verification artifacts and state-machine specs, aligned with ADR-014
- Verification result storage (VerificationResult entity with eager-loaded target/requirement, enums, CRUD API, MCP tools) - ADR-014 §2 common schema
- Pluggable verifier adapter interface (`VerifierAdapter`, `VerificationRequest`, `VerificationOutcome`) - ADR-014 §6 port contract for multi-tool integration
- Pluggable evidence collection adapter interface (`EvidenceCollectionAdapter`, `EvidenceCollectionRequest`, `EvidenceCollectionResult`) plus `EvidenceCollectionAdapterRegistry` in the evidence service package. This is the GC-S001 port contract for agent-invoked external evidence collection.
- IAM evidence adapter specification (`domain/evidence/collection/iam/`: `IamEvidenceProvider`, `IamEvidenceFamily`, `IamEvidenceSpecification`). This is the GC-S002 normative contract: it specifies - as data over the GC-S001 port, not a new adapter hierarchy - the supported providers (Okta, Azure AD, AWS IAM), the five evidence families (user access reviews, provisioning/deprovisioning events, MFA enrollment, privileged access, dormant accounts) as canonical scope types and versioned `1.0.0` output schemas, and the descriptor capabilities a conforming IAM collector advertises. Concrete provider collectors remain out of scope (see "Does not exist yet"); design rationale in `architecture/notes/iam-evidence-adapter-spec-preflight.md`.
- Cloud infrastructure evidence adapter specification (`domain/evidence/collection/cloud/`: `CloudEvidenceProvider`, `CloudEvidenceFamily`, `CloudEvidenceSpecification`). This is the GC-S003 normative contract: it specifies - as data over the GC-S001 port, not a new adapter hierarchy - the supported providers (AWS, Azure, GCP), the five evidence families (security group configurations, encryption-at-rest status, logging configurations, backup policies, compliance scan results from AWS Config / Azure Policy / GCP Security Command Center) as canonical scope types and versioned `1.0.0` output schemas, and the descriptor capabilities a conforming cloud collector advertises. Concrete provider collectors remain out of scope (see "Does not exist yet"); design rationale in `architecture/notes/cloud-infrastructure-evidence-adapter-spec-preflight.md`.
- CMDB/asset-management evidence adapter specification (`domain/evidence/collection/cmdb/`: `CmdbEvidenceProvider`, `CmdbEvidenceFamily`, `CmdbEvidenceSpecification`). This is the GC-S004 normative contract: it specifies - as data over the GC-S001 port, not a new adapter hierarchy - the supported providers (ServiceNow, Snipe-IT, Jamf), the five evidence families (asset inventory, configuration item status, patch levels, software license compliance, end-of-life tracking) as canonical scope types and versioned `1.0.0` output schemas, and the descriptor capabilities a conforming CMDB collector advertises. External CMDB/device records stay separate from `OperationalAsset` (mapping is a deliberate sync behavior through `AssetExternalId`); concrete provider collectors remain out of scope (see "Does not exist yet"); design rationale in `architecture/notes/cmdb-asset-evidence-adapter-spec-preflight.md`.
- Self-referential traceability enforcement - `check_live_policy.mjs` verifies substantive code files have reverse traceability links to requirements (GC-O002), using the `GET /requirements/traceability/by-artifact` reverse lookup endpoint. Lookup errors are tracked separately for debuggability when the endpoint is unavailable.
- Server-side quality gates (`QualityGateService.evaluate`) synced from `tools/ground_control/policy.json`, evaluated in CI (`make policy-live`) and enforced at the `/implement` completion gate via `gc_assert_quality_gates`. Enforced metric types are `COVERAGE` (IMPLEMENTS / TESTS / DOCUMENTS link coverage for ACTIVE requirements), `ORPHAN_COUNT`, and `COMPLETENESS`; a failing gate blocks the run with a `{name, metric_type, threshold, actual}` envelope.
- ADR metadata drift checks (`check_adr_drift.mjs` and `sync_policy.mjs`) use
  `tools/ground_control/common.mjs` to normalize the live ADR title from the
  API's `folder_title` field, keeping repo ADR titles and live Ground Control
  records comparable under the MCP client's response normalization.
- Research high-risk operation authorization control plane (GC-RSCH-R005 /
  GC-RSCH-N005 / GC-RSCH-N006 / GC-RSCH-N014, ADR-086). A run snapshots its
  high-risk operation policy at start (`allowedTools` inventory, structured
  default-deny `egressPolicy`, and display-only `privacyConstraints`) so later
  intake edits never re-authorize an active run. `ResearchOperationAuthorizationService`
  owns a durable, run-scoped `ResearchRunOperationAuthorization` record: a request
  lands `PROPOSED`, an admin/operator decision (an `AUTONOMOUS` run cannot
  self-approve) moves it to `APPROVED`/`DENIED` only when the run's snapshotted
  egress policy permits the `(dataClass, destinationClass, requestedForm)` tuple
  (`EgressPolicyEvaluator`, default-deny), and a one-time-use `APPROVED` record is
  spent to `CONSUMED`. Research artifacts carry an optional `dataClass`. Policy and
  authorization fields are closed enums built only from structured, service-validated
  inputs, so retrieved/untrusted content can never set tools, egress, sandbox, or
  approval state (prompt-injection as a data-flow rule). REST at
  `/api/v1/research-runs/{runId}/operation-authorizations/**` (the decision route is
  admin-gated in `ApiPathMatrix`) and the `gc_research_operation_authorization` MCP
  tool. Executors and the sandbox runtime remain out of scope (see "Does not exist
  yet").

## Knowledge Ingest Engine (repo-local, out of the product model)

Each repository that uses Ground Control can declare an agent-maintained knowledge base under `docs/knowledge/` via the `knowledge` section of its `.ground-control.yaml`. The `gc_remember` MCP tool captures observations into that repo's inbox; a detached ingest subprocess reads the inbox item, decides update-vs-create via codex, writes the wiki page, and commits the change under a per-repo interprocess lock. The engine lives at `mcp/ground-control/knowledge_ingest.js` with a thin CLI entry at `mcp/ground-control/knowledge_ingest_cli.js`.

The knowledge subsystem is deliberately repo-local tooling, not a Spring backend product feature. No REST controller, DTO, JPA entity, migration, or graph node is added by issues #522–#527. See [ADR-025](../../architecture/adrs/025-knowledge-ingest-engine.md) for the decision to co-locate the engine with the MCP server, use codex as the ingest agent, and serialize via `proper-lockfile`. Rollout phasing lives in `docs/notes/agent-knowledge-system-design.md`.
