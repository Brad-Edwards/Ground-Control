# ADR-040: Test case domain boundary

- Status: Accepted
- Date: 2026-05-16
- Driver: TC-001 (issue #669)

## Context

Ground Control needs a first-class test management subsystem (see TC-001
through TC-00X in Wave 1). TC-001 introduces the entity that anchors that
subsystem: the **test case** - a reusable, version-controlled, project-scoped
definition of an intended test (manual, automated, or hybrid).

Several adjacent aggregates already exist in the domain layer:

- `ControlTest` (ADR-039 control verification subsystem) - an executed
  control-evidence record with methodology, tester identity, conclusion,
  and a test date. It records *what happened during a control evaluation*,
  not *the definition of a reusable test*.
- `VerificationResult` (formal-methods prover output for requirements and
  traceability links).
- `TraceabilityLink` with `ArtifactType.TEST` - pointers into external or
  repo-local test code, not first-class test rows.
- `Requirement` - owns product requirements, MoSCoW priority
  (`MUST/SHOULD/COULD`), and the `DRAFT/ACTIVE/DEPRECATED/ARCHIVED`
  lifecycle.

The codex architecture preflight for #669 explicitly called out the risk of
conflating any of these with the new test-case entity:

> Do not satisfy TC-001 by adding test-case fields onto requirements,
> controls, verification results, or traceability links. A test case is
> definition-time content; execution history must remain modeled separately.

The TC-001 requirement statement also mandates a different lifecycle vocabulary
(`DRAFT / APPROVED / DEPRECATED / ARCHIVED`) and a different priority
vocabulary (severity-style, not MoSCoW) than the existing `Requirement` shape.

## Decision

Introduce a dedicated `testcases` domain at
`backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/`
following the existing `controls/` package layout
(`model/`, `state/`, `service/`, `repository/`).

The test-case aggregate is **definition-only**. It owns reusable intent;
it does not record executions, results, defects, suites, or automation
runs. Those are future aggregates that will reference test cases through
the existing project-scoped link patterns.

### Lifecycle

A dedicated `TestCaseStatus` enum with values `DRAFT`, `APPROVED`,
`DEPRECATED`, `ARCHIVED`. Transitions:

```
DRAFT     → APPROVED, ARCHIVED
APPROVED  → DEPRECATED, ARCHIVED
DEPRECATED → APPROVED, ARCHIVED
ARCHIVED  → (terminal)
```

`Requirement.Status.ACTIVE` is not the same concept as `APPROVED`: requirements
become contracts when activated, while test cases become reviewable assets when
approved. Reusing `Status` would conflate the two and would force a vocabulary
change in API/UI copy that misrepresents both lifecycles. Following the
preflight guardrail, this ADR introduces a separate enum.

### Priority

A dedicated `TestCasePriority` enum with values `CRITICAL`, `HIGH`, `MEDIUM`,
`LOW`. This matches the convention in TestRail, Zephyr Scale, Xray, qTest,
PractiTest, and Azure Test Plans - every best-of-breed test-management tool
referenced in the TC-001 rationale uses a severity-ordered vocabulary, not
MoSCoW. Reusing `Requirement.Priority` (`MUST/SHOULD/COULD`) would
mismatch every domain peer and would force test-management UI copy to read
non-idiomatically.

### Type classification

A dedicated `TestCaseType` enum with values `MANUAL`, `AUTOMATED`, `HYBRID`,
matching the requirement statement verbatim. `HYBRID` is the
manual-with-automation-stages pattern (TestRail / Xray).

### Estimated duration

Stored as `Long estimatedDurationSeconds` (column
`estimated_duration_seconds BIGINT`, nullable). One explicit, unambiguous
representation per the preflight extensibility guidance. Java code that needs
a `Duration` object converts at the boundary; clients render minutes/hours as
appropriate. Free-form strings ("about 5 minutes") are not supported - that
is the parsing hazard the preflight explicitly warned against.

### Rich text

`description`, `preconditions`, and `postconditions` are stored as `TEXT`
columns and treated as Markdown by convention. No HTML sanitizer exists in
the codebase today; rendering as HTML through `dangerouslySetInnerHTML` or
an equivalent unsafe sink is **not** introduced by this ADR. When a shared
sanitized renderer lands, the existing fields are forward-compatible.

### UID + project scoping

Test cases are project-scoped. The `(project_id, uid)` pair is unique. Lookups
by UID alone are not exposed - every read/write/list path includes
`projectId`, matching the established `Control` and `Requirement` patterns
(ADR-016).

### Audit and provenance

`TestCase` is `@Audited` (Envers) with `@NotAudited` on the `Project`
`@ManyToOne` reference, matching the pattern called out in the project-level
plan rules. The Flyway pair `V071__create_test_case.sql` +
`V072__create_test_case_audit.sql` lands the main and audit tables together.
`test_case_audit` is added to `AuditRetentionJob.AUDIT_TABLES` so retention
sweep covers it.

## Consequences

**Forward-compatible.** The aggregate has dedicated extension seams
(execution, automation binding, links, duration) that future requirements
plug into without restructuring the entity.

**Not graph-visible yet.** Graph projection (`GraphEntityType.TEST_CASE`,
mixed-graph traversal) is deferred. When test cases need to participate in
the cross-aggregate graph alongside requirements/controls/risks, a follow-on
requirement adds the projection contributor - TC-001 alone has no inbound
graph traffic to support.

**MCP surface ships with the controller.** The `controller-parity` policy
check (`tools/policy/checks.py::run_controller_contracts`) requires every new
backend controller to land alongside matching updates in `docs/API.md`,
`mcp/ground-control/lib.js`, and `mcp/ground-control/index.js`. Skipping MCP
parity is not an available option in this repo; the policy gate fails the
build. TC-001 therefore ships a small `gc_test_case` consolidated tool with
actions `create | update | delete | transition`, project-scoped through the
established `gc_query` allowlist for read paths. The exposed Zod schema mirrors
`TestCaseRequest` / `UpdateTestCaseRequest` field-for-field (including the
nullable-clear flags introduced by codex cycle 1) and the snake-case →
camelCase mapping is covered by the `TestCaseToCamelTest` MCP adapter test so
silent field drop-through (the codex finding shape) is gated. A future
test-management-MCP requirement may broaden the surface (search, filtering,
bulk operations); TC-001 keeps it to the CRUD/transition baseline that the
parity policy mandates.

**Frontend mirroring required.** Per ADR-034, the new enums (`TestCaseStatus`,
`TestCasePriority`, `TestCaseType`) are mirrored as TypeScript union types in
`frontend/src/types/api.ts` so the policy check passes.

**Audit-cleanup discipline preserved.** The retention job's `AUDIT_TABLES`
list gains exactly one entry (`test_case_audit`). The pre-existing gap for
`control_audit` / `control_link_audit` (introduced by V046/V047, never
back-filled into the retention list) is flagged on the issue thread and
deferred to a separate targeted PR - fixing two unrelated audit-retention
omissions while introducing a new domain expands the blast radius of this
change for no reviewability win.

## Alternatives considered

1. **Reuse `Requirement.Status` + `Requirement.Priority`.** Rejected per
   preflight: `ACTIVE` ≠ `APPROVED`, `MUST/SHOULD/COULD` ≠ severity, and the
   API/UI copy would diverge from every comparable test-management product.

2. **Combine TC-001 (entity) and TC-002 (step-based format) into one
   aggregate.** Rejected. TC-001 is the parent definition; TC-002 introduces
   an ordered child collection (`TestStep`). Keeping the two requirements
   in separate PRs gates the parent shape first and keeps the diff
   reviewable.

3. **Store duration as a Postgres `INTERVAL`.** Rejected. JPA mapping for
   `INTERVAL` is brittle in this codebase (no existing examples), and the
   estimated-duration semantics fit a scalar seconds counter cleanly. ISO-8601
   strings (`VARCHAR(32)`) would push parsing onto every query.

4. **Skip the type enum and store automation provenance as free-form text.**
   Rejected. The requirement statement names the three classifications
   explicitly; a free-form field defeats querying ("which manual tests are
   blocking the next sprint?") and conflicts with the preflight automation
   seam.

## Out of scope

- Test steps (deferred to TC-002, #670).
- Test execution / runs / results.
- Test suites.
- Defect / bug linkage.
- Automation runner integration.
- MCP search / filter / bulk operations beyond the CRUD/transition baseline
  registered by `gc_test_case` (the minimum the controller-parity policy
  required).
- Graph projection for test cases.
- Inline-image rich-text support beyond what raw Markdown allows.
