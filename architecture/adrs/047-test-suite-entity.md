# ADR-047: Test Suite Entity

## Status

Accepted - 2026-05-17.

## Date

2026-05-17.

## Context

TC-007 (Wave 1, MUST) requires the system to provide a Test Suite
entity supporting three population modes: static (manually selected
test cases), requirements-based (auto-populated from linked
requirements), and query-based (auto-populated from filter criteria
with dynamic updates as matching cases change).

The existing test-management aggregates from ADR-040 / ADR-041 /
ADR-042 / ADR-043 / ADR-044 cover authored test definitions
(`TestCase`, `TestCaseStep`, `TestCaseGherkin`), their repository
organisation (`TestCaseFolder`), and a planning container
(`TestPlan`). None of them is a selection container: a folder
organises definitions in a tree, a plan groups *runs* of definitions
for a release, a test case is itself a definition. There is currently
no aggregate that selects a subset of test cases for ad-hoc grouping,
coverage assertion against requirements, or dynamic filter-based
inclusion.

The architecture-preflight note
`architecture/notes/test-suite-entity-preflight.md` records the
boundaries, incumbents, and cross-cutting guardrails the design must
satisfy; this ADR consumes that note and records the load-bearing
decisions.

## Decision

Introduce a dedicated `TestSuite` aggregate inside the existing
`domain/testcases` boundary. The aggregate is project-scoped,
`@Audited`, and carries a suite-level immutable `population_mode`
invariant. Two child aggregates (`TestSuiteMember`,
`TestSuiteSourceRequirement`) carry mode-specific population state;
query-based criteria are bounded scalar columns on the root.

### Boundary and naming

- `TestSuite` is the selection container for test cases inside a
  project. It is **not** a `TestPlan`, `TestCaseFolder`,
  `Requirement`, test run, execution result, control test,
  verification result, or traceability link. The vocabulary stays
  precise:
  - `TestPlan` owns planning scope, schedule, and release coordinates.
  - `TestCaseFolder` owns repository browsing and sibling order.
  - `TestSuite` owns a population rule and the selected or resolved
    test-case members for that rule.
- The aggregate stays inside `domain/testcases`, mirroring `TestPlan`
  (ADR-044). A future architectural decision can split a wider
  `testmanagement` boundary if the surface keeps growing; that split
  is not in scope for TC-007.

### Population modes

Three modes, each with its own population source:

- **`STATIC`** - manually curated test-case membership held in
  `test_suite_member` rows. Author owns position; resolve returns
  the rows ordered by `position`.
- **`REQUIREMENTS_BASED`** - source requirements held in
  `test_suite_source_requirement` rows. Resolve resolves member test
  cases via the existing `TraceabilityLink` model (`linkType = TESTS`,
  `artifactType = TEST`); the `artifact_identifier` is the test
  case's project-scoped UID. No second requirement-link table is
  introduced just for suites.
- **`QUERY_BASED`** - structured filter criteria held as nullable
  scalar columns on `test_suite` (`criteria_status`,
  `criteria_type`, `criteria_priority`, `criteria_format`,
  `criteria_folder_id`, `criteria_text_search`). Resolve compiles
  the criteria into Spring Data `Specification` predicates against
  `TestCaseRepository` and returns the matches at read time. Query
  results are **never** persisted as canonical membership.

### Mode is a suite-level immutable invariant

`population_mode` is set on create and has no setter. Switching modes
post-create would orphan member / source / criteria state and break
the read-time-dispatch contract that consumers rely on. The
constraint is encoded:

- in the entity constructor (mode-specific field compatibility
  checks: STATIC suites reject criteria fields; REQUIREMENTS_BASED
  suites reject criteria fields; QUERY_BASED suites require at
  least one criteria field set);
- in the service (mode-mismatch operations - `addMember` on a
  REQUIREMENTS_BASED suite, `addSourceRequirement` on a STATIC
  suite, etc. - throw `DomainValidationException` with code
  `invalid_test_suite_mode_operation`);
- in the schema (`population_mode` is `NOT NULL` with a `CHECK`
  constraint over the three valid values; member / source rows
  cascade-delete when the suite is removed).

The TestPlan precedent (ADR-044, `TestPlanStatus`) is **not** copied:
TC-007 does not ask for a lifecycle status enum on the suite itself,
and adding one without a use case would be premature abstraction. A
future requirement can add a `TestSuiteStatus` enum and a transition
endpoint without disturbing the population-mode shape.

### Data model

`test_suite` (root, `@Audited`, `@NotAudited` on `project`):

- `id` (UUID PK, `BaseEntity`).
- `project_id` (FK to `project`, NOT NULL).
- `uid` (`VARCHAR(50)`, client-supplied, unique per project).
- `name` (`VARCHAR(200)`, required, non-blank).
- `description` (TEXT, optional).
- `population_mode` (`VARCHAR(20)`, NOT NULL, `CHECK IN
  ('STATIC','REQUIREMENTS_BASED','QUERY_BASED')`); immutable.
- `criteria_status`, `criteria_type`, `criteria_priority`,
  `criteria_format` (each `VARCHAR(20)`, nullable) - QUERY_BASED
  only; FK-shape to the matching `TestCaseStatus` / `TestCaseType` /
  `TestCasePriority` / `TestCaseFormat` enums.
- `criteria_folder_id` (UUID, nullable) - QUERY_BASED only; FK to
  `test_case_folder(id)`. Resolves to the folder *and all
  descendants* (read-time recursive walk) so suites can scope to a
  whole subtree.
- `criteria_text_search` (`VARCHAR(200)`, nullable) - QUERY_BASED
  only; parameterized `ILIKE` on `test_case.title` /
  `test_case.description`. Length-bounded; no raw SQL/JPQL/JSON
  predicates.
- `BaseEntity` timestamps mirrored into the audit shadow.

`test_suite_member` (`@Audited`, `@NotAudited` on `test_case`):

- `id` (UUID PK).
- `test_suite_id` (FK to `test_suite`, NOT NULL, `ON DELETE CASCADE`).
- `test_case_id` (FK to `test_case`, NOT NULL, `ON DELETE RESTRICT`).
- `position` (INTEGER, NOT NULL).
- `UNIQUE(test_suite_id, test_case_id)`; `INDEX(test_suite_id,
  position)`.
- `BaseEntity` timestamps mirrored.

`test_suite_source_requirement` (`@Audited`, `@NotAudited` on
`requirement`):

- `id` (UUID PK).
- `test_suite_id` (FK to `test_suite`, NOT NULL, `ON DELETE CASCADE`).
- `requirement_id` (FK to `requirement`, NOT NULL, `ON DELETE
  RESTRICT`).
- `UNIQUE(test_suite_id, requirement_id)`.
- `BaseEntity` timestamps mirrored.

Audit-shadow tables omit `project_id` (member rows omit `test_suite_id`
in the audit shadow too - the parent-child relationship is
reconstructable via `id` + rev).

### Resolution semantics

`GET /api/v1/test-suites/{id}/test-cases` is the load-bearing read.
It dispatches on `population_mode`:

- STATIC → JOIN `test_suite_member` to `test_case`, order by
  `position`, return mapped `TestCaseResponse` list.
- REQUIREMENTS_BASED → one batched
  `TraceabilityLinkRepository.findByRequirementIdIn(sourceIds)` call,
  filter to `linkType = TESTS AND artifactType = TEST`, batch-lookup
  the test cases by `(projectId, uid IN ...)` via a new repository
  method, deduplicate, sort by `(uid)` for determinism, return.
- QUERY_BASED → compose `TestCaseSpecifications` predicates from the
  non-null criteria columns, run via
  `TestCaseRepository.findAll(spec, sort)`, return.

A hard cap of 500 results applies in all three modes. The cap is a
service-level constant for now; a future requirement (pagination,
asset-style cursors) can promote it to a config knob or pageable
parameter without disturbing this aggregate.

Query-based resolution is **dynamic at read time**: matching test
cases change automatically as cases are added, deprecated, or
re-tagged. Results are not snapshotted; suite membership is the
*rule*, not the cached *outcome*. If a future requirement asks for
audited execution snapshots (for example "the suite that fed this test
run"), the snapshot belongs on a future `TestRun` aggregate, not on
the suite itself (preflight: "Do not persist dynamic query results
as the canonical membership unless a future caching requirement
defines invalidation, audit semantics, and staleness rules").

### Query security

QUERY_BASED criteria compile only from typed allow-listed columns
into Spring Data `Specification` predicates. The runtime accepts:

- Enum criteria (`status`, `type`, `priority`, `format`) - Jackson
  binds to the matching backend enum; an unknown value is rejected
  at the parser.
- UUID criteria (`criteria_folder_id`) - Jackson binds to `UUID`;
  service validates the folder lives in the same project before
  binding the predicate.
- Bounded text (`criteria_text_search`, max 200) - used in a
  parameterized `cb.like(cb.lower(...), "%" + lower(value) + "%")`
  call, exactly as `RequirementSpecifications.searchTitleOrStatement`.

No caller-supplied SQL, JPQL, Cypher, AGE, JSONPath, regex, or
arbitrary field names are accepted. Any future graph-aware criteria
must go through ADR-032 graph/AGE boundaries with bounded depth and
adapter-side allowlists, as the preflight requires.

### API surface

Routes live under `/api/v1/test-suites/**` so the existing auth
allow-list, IP guard, browser session / CSRF chain (ADR-037), and
actor-filter chain apply unchanged. The shared `ApiPathMatrix`
`/api/v1/**` `.authenticated()` rule covers them - no path-matrix
change is required.

- `POST /api/v1/test-suites` - create a suite (mode + per-mode
  initial state).
- `GET /api/v1/test-suites` - list suites in the resolved project.
- `GET /api/v1/test-suites/{id}` - get by UUID.
- `GET /api/v1/test-suites/uid/{uid}` - get by project-scoped UID.
- `PUT /api/v1/test-suites/{id}` - partial update of name /
  description / per-mode criteria; mode is rejected if present.
- `DELETE /api/v1/test-suites/{id}` - delete (cascades members /
  sources).
- `GET /api/v1/test-suites/{id}/test-cases` - RESOLVE; mode-dispatched.
- `POST /api/v1/test-suites/{id}/members` - STATIC: add a member.
- `DELETE /api/v1/test-suites/{id}/members/{testCaseId}` - STATIC:
  remove.
- `PUT /api/v1/test-suites/{id}/members/reorder` - STATIC: reorder.
- `POST /api/v1/test-suites/{id}/source-requirements` -
  REQUIREMENTS_BASED: add a source.
- `DELETE /api/v1/test-suites/{id}/source-requirements/{requirementId}`
 - REQUIREMENTS_BASED: remove.

There is no `PUT /{id}/mode` endpoint and no mode-transition request
DTO. Mode is immutable.

### Persistence

Six migrations (V090–V095) introduce the suite root, members,
sources, and their audit shadows. The audit shadows intentionally
omit `project_id` and the parent FK from the member / source
shadows (`@NotAudited` on those references). `AUDIT_TABLES` in
`AuditRetentionJob` gains `test_suite_audit`,
`test_suite_member_audit`, `test_suite_source_requirement_audit`.
`MigrationSmokeTest` extends its version list to 095 and adds
column-existence probes for the six new tables plus the
`UNIQUE(project_id, uid)` constraint on `test_suite`.
`RequirementsE2EIntegrationTest`'s hardcoded version list is
extended to 095.

## Consequences

- TC-007 is satisfied with one new aggregate, two child aggregates,
  and one widening of `TestCaseRepository` (it gains
  `JpaSpecificationExecutor<TestCase>` and a new
  `findByProjectIdAndUidIn` lookup used by requirements-based
  resolve).
- The selection-vs-planning-vs-organisation vocabulary stays clean:
  `TestSuite`, `TestPlan`, `TestCaseFolder` each own one concern and
  do not bleed into each other.
- Future `TestRun` work can FK to `test_suite_id` and snapshot
  resolved test-case IDs on the run side without re-shaping the
  suite. The cap-at-500 read becomes the same cap on the snapshot.
- Mode immutability is a small ergonomic cost (rename + re-create
  to "switch modes") in exchange for invariant-checked dispatch and
  zero orphaned member / source / criteria state. The cost is
  acceptable; a downstream UI can wrap the rename with a copy
  helper.
- Query-based dynamic resolution accepts the implicit liveness
  contract: "what counts" changes as test cases change. Documenting
  this on the API page and the ADR keeps consumers from expecting
  audited snapshots.

## Alternatives Considered

- **Combine all three modes into one polymorphic membership table.**
  Rejected: the storage shapes are genuinely different (STATIC needs
  ordered explicit rows; REQUIREMENTS_BASED needs requirement source
  rows; QUERY_BASED needs typed criteria with no per-case rows), and
  polymorphism here would force every mode to carry every column or
  to use JSON. Both options re-introduce the "what does this column
  mean for this mode" question on every read.
- **Reuse `TestCaseFolder` as a selection container.** Rejected:
  folders are a tree of *containers*; suites are a *flat selection*
  that can pull a test case present in several folders into one
  rule. A folder cannot be a query-based or requirements-based
  source without becoming a different aggregate.
- **Persist query-based results as canonical membership for stable
  reads.** Rejected: TC-007 explicitly says "dynamic updates as
  matching cases change." A persisted snapshot would freeze the
  membership and require a refresh mechanism (cron? manual? on
  every test-case write?) that the requirement does not ask for.
  Future `TestRun` snapshotting is the right home for "what was in
  the suite when we ran it."
- **Allow mode transitions via a `PUT /{id}/mode` endpoint.**
  Rejected: every transition would have to either drop the
  source-of-truth rows of the prior mode (data loss) or carry them
  forward as inert rows (invariant fog). The simpler model is
  immutable mode + delete-and-recreate.
- **Make `population_mode` a free-form string column.** Rejected:
  the enum is the authority for dispatch, validation, and MCP /
  frontend mirrors; a free-form column would push validation into
  every reader.

## Related

- ADR-040 (Test case domain), ADR-041 (Step format), ADR-042
  (Gherkin format), ADR-043 (Hierarchical organisation), ADR-044
  (Test Plan entity) - sibling aggregates inside the `testcases`
  boundary.
- ADR-032 (Graph / AGE adapter contracts) - applies if future
  graph-aware criteria are introduced.
- ADR-033 (Authenticated audit actor provenance) - Envers actor
  wiring.
- ADR-034 (API enum contract single source of truth) -
  `TestSuitePopulationMode` mirror discipline (manual today; the
  policy-enforced inventory currently covers only
  `domain/requirements/state/` enums, but the mirror is maintained
  for parity).
- ADR-037 (Browser session access control) - browser chain auth.
- `architecture/notes/test-suite-entity-preflight.md` - design
  input.
