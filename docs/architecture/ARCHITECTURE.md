# Ground Control—Architecture

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
| Registry | GHCR (`ghcr.io/brad-edwards/ground-control`) |

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
- `api/` depends on `domain/`—never imports `infrastructure/`
- `infrastructure/` implements interfaces defined in `domain/`

Enforced at compile time by ArchUnit tests in `ArchitectureTest.java`.

## Configuration

Spring profiles drive environment-specific behavior:

- `application.yml`—base config (datasource, JPA, Flyway, server port, security defaults)
- `application-dev.yml`—local dev (`groundcontrol.security.enabled=false`)
- `application-test.yml`—test overrides (Testcontainers, security disabled)

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
checks—the one deliberate exception,
`PackRegistryAccessGuard`, is a defense-in-depth bridge that re-derives the
admin principal from the same `SecurityContext` and re-asserts `ROLE_ADMIN`
(see ADR-033 §4). `ActorFilter` runs after the security chain so audit
identity tracks the authenticated principal; it writes the principal to MDC
key `actor_id`, the key `logback-spring.xml`'s production JSON appender
exports (alongside `request_id` / `tenant_id`). See [ADR-033](../../architecture/adrs/033-authenticated-audit-actor-provenance.md).

## What Exists vs. What Doesn't

### Exists

**Domain entities:** Requirement, RequirementRelation, TraceabilityLink, GitHubIssueSync, RequirementImport—all JPA with Envers auditing.

**Services:** RequirementService (9 methods), TraceabilityService (forward and reverse artifact lookup), ImportService (StrictDoc parser + idempotent import), GitHubIssueSyncService (CLI-based GitHub sync), AnalysisService (cycle/orphan/coverage/impact/cross-wave; status drift belongs here as read-only analysis), AgeGraphService (Apache AGE graph materialization + Cypher queries).

**API:** RequirementController (9 REST endpoints), AnalysisController (5 endpoints), ImportController, SyncController, GraphController. GlobalExceptionHandler maps domain exceptions to HTTP error envelopes.

**Frontend:** React 19 / TypeScript SPA served as embedded static resources from the Spring Boot JAR. Views: Dashboard (project health metrics), Requirements Explorer (browse/filter/author), Requirement Detail (fields, relations, traceability, audit), Dependency Graph (Cytoscape.js DAG visualization). See [ADR-017](../../architecture/adrs/017-interactive-web-application.md).

**Tooling:** Status state machine with JML contracts (verified by OpenJML ESC + Z3), Flyway migrations, Spotless/Error Prone/SpotBugs/Checkstyle/JaCoCo, ArchUnit architecture tests, CI pipeline (build + test + integration + verify), production Dockerfile, GHCR publishing, E2E integration tests.

## Mixed-Entity Graph Participants

The mixed-entity graph (materialized via `AgeGraphService` + Apache AGE) now includes the following first-class domain participants, each backed by a `GraphProjectionContributor` that emits typed nodes into the project-scoped graph: Requirement, OperationalAsset (and Observation), RiskScenario, Control, ControlTest, ControlEffectivenessAssessment, VerificationResult, ThreatModel, Finding, EvidenceArtifact, Audit, RiskControlMapping, and Document (added GC-G007). GitHub Issues, external code references, and other artifacts without a backend aggregate remain external targets addressed by identifier only, not first-class graph nodes. Every first-class participant exposes a stable `graphNodeId` field on its REST response (via `GraphIds.nodeId`) for client-side graph navigation. Property keys emitted by contributors must be registered in `AgeGraphService.APPROVED_PROPERTY_KEYS` (ADR-032), and each new contributor must ship a regression test asserting that registration.

## Mixed-Entity Graph Operations

The four public operations on the mixed-entity graph (GC-G008) are:

- `GET /api/v1/graph/visualization`—returns the full project-scoped graph as a flat node+edge list.
- `POST /api/v1/graph/subgraph/query`—extracts a subgraph anchored at caller-supplied root node IDs.
- `POST /api/v1/graph/traversal/query`—BFS neighborhood traversal with configurable depth and optional entity-type filter.
- `POST /api/v1/graph/paths/query`—shortest-path queries between two node IDs.

**Routing:** `GraphController` → `MixedGraphService` → `MixedGraphClient` → `AgeGraphService` (with JPA-projection fallback when Apache AGE is unavailable, per ADR-032). The JPA fallback builds the same `GraphProjection` shape from JPA aggregates so callers receive a consistent response regardless of AGE availability.

**Node IDs** follow the form `GraphEntityType:UUID` (for example, `CONTROL:a1b2c3d4-…`), produced by `GraphIds.nodeId`. All four endpoints accept node IDs in this format; IDs are validated against the resolved project's projection (not globally), so cross-project references are always rejected.

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

- Requirements are project-scoped per ADR-016; all status drift queries must resolve a single project, and every evidence signal must be derived from data owned by that project. Never compare UIDs across projects without project context, and never read project- or repo-unscoped caches (for example, the GitHub issue/PR sync tables) from this path—a project-scoped analysis must not surface another project's (or another repo's) artifacts.
- Evidence is link-based: an `IMPLEMENTS` traceability link on a `DRAFT` requirement (the strongest signal—`IMPLEMENTS` to an issue is allowed pre-`ACTIVE` in the GC-O007/#794 shape), a `DOCUMENTS` link to an `ACCEPTED` ADR (`ArchitectureDecisionRecord` + `TraceabilityLink` with `artifactType=ADR`, `linkType=DOCUMENTS`), a non-`IMPLEMENTS` link to a GitHub issue or pull request, or a non-`IMPLEMENTS` link to a code/test/spec/proof artifact. A `DOCUMENTS` link is evidence, not an implementation link.
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
- Traceability Matrix view (`/traceability`) and Audit Timeline view (`/audit`) in the frontend
- Apache AGE is optional—the app gracefully degrades to JPA-only analysis when AGE is unavailable

### Exists now

- `specs/tla/` for design-level verification artifacts and state-machine specs, aligned with ADR-014
- Verification result storage (VerificationResult entity with eager-loaded target/requirement, enums, CRUD API, MCP tools)—ADR-014 §2 common schema
- Pluggable verifier adapter interface (`VerifierAdapter`, `VerificationRequest`, `VerificationOutcome`)—ADR-014 §6 port contract for multi-tool integration
- Self-referential traceability enforcement—`check_live_policy.mjs` verifies substantive code files have reverse traceability links to requirements (GC-O002), using the `GET /requirements/traceability/by-artifact` reverse lookup endpoint. Lookup errors are tracked separately for debuggability when the endpoint is unavailable.

## Knowledge Ingest Engine (repo-local, out of the product model)

Each repository that uses Ground Control can declare an agent-maintained knowledge base under `docs/knowledge/` via the `knowledge` section of its `.ground-control.yaml`. The `gc_remember` MCP tool captures observations into that repo's inbox; a detached ingest subprocess reads the inbox item, decides update-vs-create via codex, writes the wiki page, and commits the change under a per-repo interprocess lock. The engine lives at `mcp/ground-control/knowledge_ingest.js` with a thin CLI entry at `mcp/ground-control/knowledge_ingest_cli.js`.

The knowledge subsystem is deliberately repo-local tooling, not a Spring backend product feature. No REST controller, DTO, JPA entity, migration, or graph node is added by issues #522–#527. See [ADR-025](../../architecture/adrs/025-knowledge-ingest-engine.md) for the decision to co-locate the engine with the MCP server, use codex as the ingest agent, and serialize via `proper-lockfile`. Rollout phasing lives in `docs/notes/agent-knowledge-system-design.md`.
