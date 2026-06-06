# ADR-056: Research project type and intake metadata

## Status

accepted

## Date

2026-05-26

## Context

Ground Control projects are the top-level workspace abstraction. Until now the `project` entity carried only `identifier`, `name`, and `description`—software, GRC, and (after ADR-055) research workspaces were distinguished by convention, not by schema. The acceptance criteria for issue #999 require:

- a user can create or update a project as type Research,
- research intake fields are persisted and returned through the API,
- research projects appear in list/detail responses with their project type,
- non-research projects require no new research-only fields to remain valid.

Five requirements scope the work: `GC-RSCH-F001` (intake fields: research goal, paper context, target contribution type, intended output, autonomy level, allowed tools, privacy constraints, cost/compute budget), `GC-RSCH-R007` (distinguish literature-based work from experiment-running auto-research), and three forward-looking ones (`GC-RSCH-R001` workflow phases, `GC-RSCH-F002` task classification, `GC-RSCH-N011` observability) that describe research workflow internals which assume the intake foundation this ADR delivers exists. The three forward-looking requirements remain DRAFT after this PR; subsequent issues implement the workflow on top of the foundation.

ADR-055 (research workflow skills + citation MCP) shipped the agent-side workflow but explicitly deferred the Java-domain project-type registration to a separate ADR when a typed enum is later wanted. This is that ADR.

## Decision

### 1. `ProjectType` as a closed enum

Add a `type` column to `project` typed as a closed `ProjectType` enum with three values: `SOFTWARE`, `GRC`, `RESEARCH`. The column is `NOT NULL`. Migration V126 backfills existing rows to `SOFTWARE` (the implicit historical default before this PR). New types require an ADR + migration; the enum is closed at the API boundary (invalid values produce 422 with `validation_error`).

`SOFTWARE` is the default for new projects when the client omits `type`, preserving backward compatibility with API clients that do not set the field.

### 2. `ResearchIntake` is a separate aggregate, 1:1 with `Project`

Research intake metadata (~10 fields) lives in a new `ResearchIntake` entity at `domain/research/model/ResearchIntake.java`, joined 1:1 with `Project` by `project_id`. The intake row exists if and only if `Project.type = RESEARCH`. Rejected alternatives:

- **Nullable columns on `Project`.** Adding 10 research-only nullable columns to `Project` makes the entity a junk-drawer that mixes type-agnostic identity (`identifier`, `name`, `description`) with type-specific intake. Every read of `Project` would need to branch on `type` to know which columns are meaningful. Validation would split across the boundary.
- **JPA Single Table Inheritance (`SoftwareProject`, `GrcProject`, `ResearchProject`).** Ground Control's domain pattern is flat aggregates inheriting `BaseEntity` with explicit references (for example, `TreatmentPlan` is a sibling of `RiskScenario`, not a subclass of `Project`). STI would be net-new architecture for a single type-specific extension.
- **JSON `intake` column on `Project`.** Defeats Bean Validation on the structured intake fields and loses Envers history per field. The codebase only uses JSON columns for fundamentally polymorphic collections (for example, `action_items` on `TreatmentPlan`), not for structured records.

A separate aggregate keeps `Project` flat, keeps research-only validation localised, and lets `ResearchIntake` carry its own Envers history.

### 3. `ResearchIntake` is `@Audited`; `Project` stays non-audited

Changes to research intake matter for provenance once the workflow phases run on top (per `GC-RSCH-N011`'s observability requirement). `Project` itself remains non-audited (unchanged from current state). The `@ManyToOne` reference from `ResearchIntake` to `Project` is `@NotAudited`, matching the plan rule and the existing `TreatmentPlan → Project` pattern. Migration V127 creates `research_intake_audit` via the standard Envers shadow-table pattern.

### 4. Enums for closed vocabularies under `ResearchIntake`

- `ContributionType`: `TAXONOMY`, `REVIEW`, `EMPIRICAL_STUDY`, `METHODOLOGY`, `POSITION`, `OTHER`.
- `IntendedOutput`: `SCOPING_REVIEW`, `SYSTEMATIC_REVIEW`, `SYSTEMATIC_MAP`, `CRITICAL_REVIEW`, `NARRATIVE_REVIEW`, `TARGETED_RELATED_WORK`, `TAXONOMY_PAPER`, `OTHER`. The seven non-`OTHER` values mirror the seven method keys in `skills/lit-review/methodology/catalog.yaml` so the downstream `lit-review` phase-1 skill can derive a methodology choice from the intake. `OTHER` keeps the enum open at the edges without making the field free-form.
- `AutonomyLevel`: `COPILOT`, `AUTONOMOUS`. Matches the user-gate vocabulary in the lit-review skills.

Closed enums match how the codebase represents `TreatmentStrategy`, `TreatmentPlanStatus`, `ActionItemStatus`—invalid values are 422 at the API boundary with a `validValues` hint, the existing `ErrorResponse` envelope shape.

### 5. `allowedTools` as a typed `Set<String>`

Tool identifiers (for example, `cite_resolve`, `python`, `web_search`) are operator-extensible and not a fixed vocabulary, so they cannot be an enum. Stored as a Jackson-converted `Set<String>` on a TEXT column using the existing `JacksonTextCollectionConverters.StringListConverter` pattern. Set semantics (uniqueness, no order) match the meaning of the tools the operator has authorised. An empty set means no tools authorised (the run is read-only and has no side effects); a `null` set is rejected by Bean Validation when `type = RESEARCH`.

### 6. Budget fields are typed and optional

- `budgetTokens BIGINT` (nullable)—token cap; `null` means unbounded.
- `budgetWallClockMinutes INTEGER` (nullable)—wall-clock cap; `null` means unbounded.
- `budgetCostUsdMicros BIGINT` (nullable)—cost cap in USD micros (1 USD = 1,000,000 micros). `BIGINT` micros avoid `DECIMAL` round-trips in Jackson and Postgres-driver edge cases; conversion to USD for display is the API/UI's job.

Each is individually optional. Service-layer logging records when an intake is created with no caps (`research_intake_created: no_budget_caps=true`) so operators can audit unbounded runs.

### 7. "Intake required iff type = RESEARCH" enforcement

Two layers:

- **Bean Validation at the API boundary** (`ProjectRequest`, `UpdateProjectRequest`). A custom class-level constraint `@ResearchIntakeRequired` rejects payloads where `type = RESEARCH` and `researchIntake` is null, or where `type != RESEARCH` and `researchIntake` is non-null. 422 with the existing `validation_error` envelope shape.
- **Service-layer guard** (`ProjectService.validateIntakeAgainstType`). Mirrors the same rule for bypass writes that skip API validation (programmatic creates from tests, migrations, or future intra-backend callers). This is the same defence-in-depth pattern PR #997 used for typed action items: API constraint + service guard.

`update` allows `type` to change only via an explicit `changeProjectType` operation (out of scope for this PR—no AC requires it). Updating intake fields on an existing RESEARCH project goes through `PUT /api/v1/projects/{identifier}/research-intake`, decoupled from project name/description updates so each field has clean change tracking.

### 8. API shape

- `POST /api/v1/projects`—accepts `{identifier, name, description, type?, researchIntake?}`. `type` defaults to `SOFTWARE` if omitted.
- `GET /api/v1/projects` and `GET /api/v1/projects/{identifier}`—include `type` always; `researchIntake` is present when `type = RESEARCH`, absent otherwise.
- `PUT /api/v1/projects/{identifier}`—name/description updates (unchanged plus the validation guard).
- `PUT /api/v1/projects/{identifier}/research-intake`—full replacement of the intake row for a RESEARCH project; 422 if the project is not RESEARCH (semantic validation error, not a resource-state conflict); 404 if the project doesn't exist or has no intake; 422 on field-level validation errors.

Per the plan rules, every new endpoint ships with `@WebMvcTest` controller slice tests (the sonar CI job does not run Testcontainers).

## Consequences

- Existing rows in `project` get `type = SOFTWARE` via V126. Existing API clients that omit `type` continue to get `SOFTWARE`. No breaking change for non-research callers.
- `GC-RSCH-F001` and `GC-RSCH-R007` transition DRAFT → ACTIVE with this PR. `GC-RSCH-R001`, `GC-RSCH-F002`, `GC-RSCH-N011` stay DRAFT with `DOCUMENTS` links to issue #999; subsequent issues materialise the workflow phases, task classification, and observability on top of the intake foundation this ADR delivers.
- Future work—phase state machine, task classification per phase, observability of gates and cost—extends `ResearchIntake` and adds sibling aggregates (for example, `ResearchRun`, `PhaseGate`) rather than re-shaping `Project`.
- The frontend gets the project type in every list/detail response and can route to research-specific intake forms. Frontend work is out of scope for this PR (no UI is required for backend AC); the React side picks this up in a separate issue.

## Alternatives considered

**Allow `type` to change post-creation via plain `PUT /api/v1/projects/{id}`.** Rejected: changing a project's type after creation has cascading effects on adjacent data (a RESEARCH→SOFTWARE flip would orphan the intake row; a SOFTWARE→RESEARCH flip would require synthesising an intake from nothing). Out of scope for this PR; if a use case appears, a dedicated `changeProjectType` operation with explicit operator authorisation is the right shape.

**Make `type` part of the `identifier` (`research/foo`, `software/bar`).** Rejected: identifier is currently a single segment; restructuring it would break every external reference (Linear, dashboards, URLs, traceability links—the `project_identifier` field in every requirement currently carries the unsegmented form). The schema column `type` is the right tool.

**Store the seven `IntendedOutput` values by reference to `skills/lit-review/methodology/catalog.yaml`.** Rejected: the backend should not load + parse a skill-side YAML at runtime; the enum is the contract, the catalog is the agent-side method-source mapping that consumes the enum's choices. If the catalog adds an eighth method, that's an enum addition (ADR + migration) plus a catalog entry—both are deliberate decisions.
