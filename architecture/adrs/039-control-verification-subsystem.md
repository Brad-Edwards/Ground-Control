# ADR-039: Control Verification Subsystem (Tests + Effectiveness Assessments)

## Status

Accepted (2026-05-13)

## Context

`Control` is the catalog row for a control (objective, function, status, owner,
implementation scope). It tells you *what* a control is - not whether it was
ever tested or whether it works.

Two adjacent durable concerns were missing:

- **Control testing (GC-I012).** Audit/compliance workflows need a per-test
  evidence record that captures the methodology used (inquiry, observation,
  inspection, re-performance - PCAOB AS 2201), the test steps, the expected and
  actual results, the conclusion, the tester identity, and the test date.
  Auditors point at specific test rows when defending an opinion.

- **Control effectiveness (GC-I013).** SOC 2 Type II / SOX testing requires a
  separate judgment about whether a control is **designed** to address its risk
  (design effectiveness) and whether observed operation shows it actually works
  (operating effectiveness). A control can be well-designed but poorly operated
  or vice versa, so the two judgments cannot collapse into a single rating.

The preflight note at
`architecture/notes/control-testing-entity-preflight.md` worked the
boundaries against `Observation`, `VerificationResult`,
`RiskAssessmentResult`, and `Control.effectiveness` (a free-form JSON
projection field). This ADR records the durable shape that landed.

## Decision

Two separate first-class audited aggregates:

1. `domain.controls.model.ControlTest` (GC-I012).
2. `domain.controls.model.ControlEffectivenessAssessment` (GC-I013).

Both extend `BaseEntity`, are `@Audited` with the standard Envers pattern
(`@NotAudited` on `@ManyToOne` references to non-audited `Project`/`Control`),
and live in the `controls` package alongside `Control` because they share its
aggregate root.

### Why two aggregates, not one

The two have different lifecycles and different consumers:

- A `ControlTest` is *one execution* of a test plan. There can be many tests
  per control over time. The fields are step-grained (steps, expected, actual,
  conclusion) and methodology-grained.
- A `ControlEffectivenessAssessment` is a *rating* - a judgment that may
  consume zero or more tests as supporting evidence but is its own decision
  with its own assessor. Its consumers are different (future GC-T003
  risk-scoring code reads ratings, not test steps).

Conflating them - for example, adding rating fields to `ControlTest` - would force
auditors to interpret the rating from execution details and would block GC-T003
from reading a clean rating signal.

### Why not collapse into `Control.effectiveness`

`Control.effectiveness` is a free-form `Map<String, Object>` that can summarize
the *current* rating for convenience. It is **not** the source of truth:

- It is mutable in place - no audit history of who changed what when, beyond
  the parent control's revision history.
- It has no methodology, no assessor, no rationale.
- It cannot represent multiple tests / multiple assessments.

The ADR keeps `Control.effectiveness` as an optional projection of the latest
assessment state, not the assessment history.

### Boundaries against adjacent aggregates

- `Observation`: an asset/system fact. A `ControlTest` may *use* observations
  as evidence, but it is not an observation - observations have no
  methodology, no expected/actual structure, no tester identity.
- `VerificationResult`: prover / requirement evidence. Not a control test.
- `RiskAssessmentResult`: methodology-specific risk computation output.
  Effectiveness assessments **feed** risk scoring via GC-T003 (see Seam below);
  they are not the risk computation itself, and writing residual scores from
  effectiveness controllers is explicitly forbidden.

### GC-T003 consumption seam

`ControlEffectivenessAssessment.operatingEffectiveness` is the stable,
audited, project-scoped read target that future GC-T003 risk-scoring code
consumes. GC-T003 itself is still DRAFT; this ADR commits the field name, the
enum (`EFFECTIVE` / `PARTIALLY_EFFECTIVE` / `INEFFECTIVE`), and the per-control
scope so the GC-T003 implementation has a stable interface to read from. The
actual residual-risk feed is deliberately not in this PR; it lands when GC-T003
materializes.

A future enhancement that needs methodology-specific factor influence (FAIR
frequency/magnitude factors, qualitative likelihood/consequence) can add a
nullable `methodology_profile_id` FK and a JSON factor-influence column to the
assessment table via additive migration. The seam is open; no schema break is
required.

### Tester identity vs. audit actor

`testerIdentity` (on `ControlTest`) and `assessor` (on
`ControlEffectivenessAssessment`) are **domain provenance** - the human or
process named in the audit narrative. They do **not** replace the
authenticated audit actor on the Envers revision record (per ADR-033). Both
exist intentionally: the audit actor is *who pushed the row*; the domain
provenance is *who did the work*. Sometimes they are the same person;
sometimes the auditor logs a row on behalf of a tester.

### REST surface

- `/api/v1/control-tests` - POST / GET `{id}` / GET (with optional `controlId`
  filter) / PUT / DELETE.
- `/api/v1/control-effectiveness-assessments` - same shape.

No nested-under-control routes. The `controlId` is a body field on create and a
query parameter on list. The flat surface mirrors `gc_risk_governance` /
`gc_control` and keeps URL shapes uniform across the aggregate family.

### MCP surface (ADR-035 compliance)

`gc_control` gains an `entity` discriminator (defaults to `control` for
back-compat) with values `control` / `control_test` /
`control_effectiveness_assessment`. Per-entity per-action field allowlists
(`CONTROL_FIELDS`) mirror the backend Request records. Sub-entities support
create/update/delete only; reads route through `gc_query`. The handler logic
is extracted into `mcp/ground-control/gc-control.js` (mirroring the
`gc-risk-governance.js` extraction from #878) so the dispatch is testable in
isolation. No new top-level MCP tool is added - the consolidated surface is
preserved per ADR-035.

### Graph projection

Each aggregate has its own `GraphProjectionContributor`:
`ControlTestGraphProjectionContributor` and
`ControlEffectivenessAssessmentGraphProjectionContributor`. Both emit a node
per row plus a single outgoing edge (`OF_CONTROL`) to the parent control. No
separate link table exists; the parent `control_id` FK is the authoritative
relationship. Downstream traversals discover history by traversing inverse
edges.

## Consequences

- Audit/compliance workflows have a durable, per-execution record of every
  control test, with methodology lineage suitable for auditor defense.
- Risk-engine integration (GC-T003) can read a stable operating-effectiveness
  rating without parsing test row internals.
- `Control.effectiveness` is now explicitly a projection field, not source of
  truth. Frontends can keep using it for the current-rating glance.
- The graph gains two new node types and a stable edge label
  (`OF_CONTROL`); downstream analytics can traverse Control →
  test/assessment history.
- Future FAIR/qualitative factor-influence work has an open seam: add columns
  to the assessment table or its links; no schema break required.

## Related

- Preflight note: `architecture/notes/control-testing-entity-preflight.md`.
- Requirements: GC-I012 (Control Testing Entity), GC-I013 (Control
  Effectiveness Assessment), GC-T003 (Risk Scenario-Control Mapping; DRAFT;
  consumes operating effectiveness).
- ADRs: ADR-033 (Actor provenance), ADR-034 (Enum mirror policy), ADR-035 (MCP
  tool catalog curation).
- Issues: #270 (GC-I012), #271 (GC-I013).
