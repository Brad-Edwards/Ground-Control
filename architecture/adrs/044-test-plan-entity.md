# ADR-044: Test Plan Entity

## Status

Accepted - 2026-05-17.

## Context

TC-006 (Wave 1, MUST) requires the system to provide a Test Plan entity
that serves as the top-level planning container for a testing effort,
carrying a unique ID, name, description, associated product / version /
build, status, planned start / end dates, and the ability to group
multiple test runs under a single plan.

The existing test-management aggregates (`TestCase`, `TestCaseStep`,
`TestCaseGherkin`, `TestCaseFolder` from ADR-040 / ADR-041 / ADR-042 /
ADR-043) cover authored test definitions and their repository
organisation. None of them is a planning container: a folder organises
definitions, a test case is a definition, a step is authored content, a
Gherkin doc is authored content. There is currently no aggregate that
groups *runs* of those definitions for a specific release.

Ground Control has no `Product`, `Release`, `Version`, or `Build`
aggregate either. Generic test-management tools sometimes model these
catalogs as first-class entities; Ground Control does not, and TC-006
does not ask us to.

## Decision

Introduce a dedicated `TestPlan` aggregate inside the existing
`domain/testcases` boundary. The aggregate is project-scoped, `@Audited`,
flat (plans do not nest), and carries its release coordinates as bounded
scalar text fields.

### Boundary and naming

- `TestPlan` is the top-level **planning** container for a testing
  effort: it owns scope, schedule, and release coordinates. It is
  **not** a `TestCase`, test-case folder, authored format, verification
  result, control test, or traceability link.
- The aggregate stays inside the `domain/testcases` boundary rather
  than introducing a new `domain/testplans` package. Test plans, test
  cases, folders, steps, and Gherkin docs are different shapes of the
  same testing problem, and a single boundary keeps the
  shared infrastructure (project scope, audit, validation patterns) in
  one place. A future architectural decision can split a wider
  `testmanagement` boundary if the surface keeps growing; that split
  is not in scope for TC-006.
- Plans are intentionally flat. The TC-006 statement says "Test plans
  are the top-level organizational container"; nesting plans inside
  plans would change that contract and complicate the eventual
  TestRun-grouping relationship.

### Data model

The `TestPlan` entity carries:

- `id` (UUID PK, `BaseEntity`) - the load-bearing extensibility seam
  (see below).
- `project_id` (FK to `project`) - project scope, mirroring every
  other test-management aggregate. `@NotAudited` on the JPA mapping.
- `uid` (`VARCHAR(50)`, client-supplied, unique per project) - same
  convention as `TestCase.uid`. The pair `(project_id, uid)` is
  uniquely indexed; the service throws `ConflictException` on a
  duplicate.
- `name` (`VARCHAR(200)`, required, non-blank).
- `description` (TEXT, optional).
- `product`, `version`, `build` - bounded scalar text columns
  (`VARCHAR(200)`, `VARCHAR(100)`, `VARCHAR(100)` respectively), all
  optional. These are *release coordinate text*, not foreign keys
  into a Product / Release / Build catalog; see the next section for
  the rationale.
- `status` (enum, `TestPlanStatus`, default `DRAFT`).
- `start_date`, `end_date` (`LocalDate`, optional) with an
  `end_date >= start_date` cross-field invariant enforced in the
  entity setters.
- `BaseEntity` timestamps mirrored into the audit shadow.

### Release coordinates as bounded scalars

`product`, `version`, and `build` are stored as bounded `VARCHAR`
columns on `TestPlan` rather than foreign keys into a catalog. There is
no existing aggregate to FK to, and introducing one purely to satisfy
TC-006 would (a) force a release-management surface design we have not
been asked for, (b) couple `TestPlan` to schema we cannot yet validate
against, and (c) make the simple-case form (a free-text version label
on a one-off plan) more complex than the requirement. If a future
requirement promotes release coordinates into a catalog, the migration
is straightforward: add the catalog aggregate, add nullable FK columns
to `test_plan`, keep the text columns for one release, then drop them.

### Status lifecycle

A dedicated `TestPlanStatus` enum mirrors the `TestCaseStatus` API
(`validTargets()` / `canTransitionTo()`) but with execution-tracking
semantics, since a plan describes an *effort* rather than an *authored
definition*:

- `DRAFT` → `ACTIVE`, `ARCHIVED`
- `ACTIVE` → `IN_PROGRESS`, `COMPLETED`, `ARCHIVED`
- `IN_PROGRESS` → `ACTIVE`, `COMPLETED`, `ARCHIVED`
- `COMPLETED` → `ACTIVE`, `ARCHIVED`
- `ARCHIVED` → ∅ (terminal soft-delete)

Two backwards arcs are deliberate. `IN_PROGRESS → ACTIVE` lets a team
pause a run window without losing the plan. `COMPLETED → ACTIVE`
lets a team re-open a plan when late-arriving runs need to be folded
in; the alternative (force a brand-new plan with a different uid)
would lose the audit linkage that makes the prior runs traceable.

We did not reuse `TestCaseStatus`, `Requirement.Status`, or
`TreatmentPlanStatus`. Their words describe authored-definition
lifecycles (`DRAFT`/`APPROVED`/`DEPRECATED`/`ARCHIVED`) rather than
the execution lifecycle a plan needs.

### Extensibility - the load-bearing seam

The TC-006 clause "ability to group multiple test runs under a single
plan" is satisfied by `TestPlan.id` being a stable UUID PK that a
future `TestRun` aggregate's `test_plan_id` column can FK back to.
No JSON array of run IDs lives on the plan; no placeholder `TestRun`
aggregate is created in this ADR. When the TestRun requirement lands,
its migration adds a column to `test_run` (or its predecessor) and
the seam is engaged without amending `TestPlan` at all.

This is the same shape `TestCase` uses for grouping authored children
(`TestCaseStep`, `TestCaseGherkin`): the parent carries a stable PK,
the child carries the FK. The pattern keeps the parent aggregate
small and lets each child aggregate own its own lifecycle.

### API surface

Routes live under `/api/v1/test-plans/**` so the existing auth
allow-list, IP guard, browser session / CSRF chain (ADR-037), and
actor-filter chain apply unchanged:

- `POST /api/v1/test-plans` - create a plan in the resolved project.
- `GET /api/v1/test-plans` - list plans for the resolved project,
  ordered by `createdAt DESC` (most-recent-first).
- `GET /api/v1/test-plans/{id}` - get by UUID.
- `GET /api/v1/test-plans/uid/{uid}` - get by project-scoped UID.
- `PUT /api/v1/test-plans/{id}` - partial update; null leaves a field
  alone, `clearXxx: true` clears it (matches `UpdateTestCaseRequest`).
- `PUT /api/v1/test-plans/{id}/status` - transition status; legality
  delegated to the entity.
- `DELETE /api/v1/test-plans/{id}` - delete plan.

### Persistence

Two migrations (V088–V089) introduce the plan table and its audit
shadow. The audit shadow intentionally omits `project_id`
(`@NotAudited` on `TestPlan.project`), mirroring the
`test_case_folder_audit` shape. `AUDIT_TABLES` in `AuditRetentionJob`
gains `test_plan_audit`; `MigrationSmokeTest` covers the new versions
and pins the columns most likely to silently regress via
`information_schema` probes. `RequirementsE2EIntegrationTest`'s
hardcoded version list is extended.

## Consequences

- TC-006 is satisfied without inventing a Product / Release / Build
  catalog. A future release-management requirement can promote those
  coordinates without amending plan identity.
- Future TestRun work has a stable target to FK to.
- Plans live alongside test cases in the `testcases` boundary. If
  test-management surface keeps growing (test runs, defect tracking,
  automation runners, evidence uploads), a future ADR can split a
  `testmanagement` boundary; doing so is not load-bearing today.
- Status backwards arcs let plans be re-opened or re-paused without
  breaking audit linkage.

## Alternatives Considered

- **Reuse `TestCaseFolder` as a planning container.** Rejected:
  folders organise authored definitions; plans organise *runs*. The
  two concerns share project scope and audit semantics but have
  distinct lifecycles. Conflating them would force every folder to
  carry release coordinates and a planning status it does not need.
- **JSON array of run IDs on `TestPlan`.** Rejected: violates the
  preflight non-goal explicitly, and run cardinality is unbounded.
  A FK on the run-side aggregate is the standard relational shape
  and keeps the plan aggregate small.
- **First-class `Product` / `Release` / `Build` aggregates.**
  Rejected for TC-006: no such requirement exists today, and the
  blast radius of designing them now without a use case would block
  this requirement on unrelated work. A future requirement can
  promote them with a focused migration; the text columns will stay
  valid until then.
- **Reuse `TestCaseStatus` for plans.** Rejected: the words
  (`APPROVED`, `DEPRECATED`) describe an authored-definition
  lifecycle. A plan's lifecycle is execution-oriented and the
  vocabulary should match.

## Related

- ADR-040 (Test case domain), ADR-041 (Step format), ADR-042 (Gherkin
  format), ADR-043 (Hierarchical organisation) - sibling aggregates
  inside the `testcases` boundary.
- ADR-033 (Authenticated audit actor provenance) - Envers actor wiring
  that `TestPlan` inherits unchanged.
- ADR-034 (API enum contract) - `TestPlanStatus` is single-sourced in
  the domain state package and mirrored in `frontend/src/types/api.ts`.
- ADR-037 (Browser session access control) - the session / CSRF chain
  that `/api/v1/test-plans/**` rides through.
