# ADR-057: Decision Analysis Record Boundary

## Status

Accepted

## Date

2026-05-30

## Context

GC-W011 requires recording decision analyses; inputs, models, simulation
parameters, results, alternatives, chosen alternative, and rationale; so a
team can retrospectively review decision quality, support calibration
tracking, and link decisions to the requirements and ADRs they shaped.

Three naming and boundary collisions must be avoided:

- `domain/audit/` is the Hibernate Envers infrastructure package. A
  `Decision` aggregate placed there would collide with the existing audit
  service surface.
- `gc_post_decision_record` is the workflow gate that records a
  pre-decision/post-decision marker on a GitHub issue thread. That tool name
  governs a process artifact, not an aggregate, and naming a domain
  aggregate `DecisionRecord` would conflate the two.
- A `DecisionAnalysis` placed under `riskscenarios/` or `grcanalysis/` would
  imply it is a risk computation, blurring the line between "the analysis
  we ran" (FAIR / NIST / WSJF) and "the decision we made from the
  analysis."

## Decision

### 1. Live at `domain/decisions/` (plural)

The aggregate is `DecisionAnalysisRecord` under `domain/decisions/`. The
plural form deliberately avoids `domain/decision/` (which would clash with
the workflow gate string `"decision_record"` and read ambiguously next to
`domain/audit/`).

The REST surface is `/api/v1/decisions/**`; also plural for the same
reason. The MCP gc_query allowlist gains `/api/v1/decisions`.

### 2. Capture the model name, parameters, inputs, results, alternatives, chosen alternative, and rationale

A `DecisionAnalysisRecord` carries:

- `uid` (project-scoped) and `title`
- `modelName`; the analytical model used (`monte_carlo`, `fair`,
  `wsjf`, `nist_sp_800_30`, `expert_judgment`, etc.) so the record points
  back to a reproducible computation
- `summary`; short description for human consumption
- `inputs`; named estimate inputs with attribution metadata; estimator
  identity flows through ActorHolder (ADR-033)
- `simulationParameters`; model-specific knobs (seed, iterations, etc.)
  so a reviewer can re-run the computation
- `results`; output summary (quantiles, scenario rankings)
- `alternatives`; the alternatives considered
- `chosenAlternative`; the one selected
- `rationale`; the reasoning that led to the choice

The shape is intentionally flexible (`Map<String, Object>` for inputs,
parameters, and results) because the recorded analyses span FAIR, NIST,
WSJF, and ad-hoc qualitative models. The audit trail is the primary
consumer; downstream visualization is a follow-up.

### 3. Link to other artifacts via existing traceability

A new `ArtifactType.DECISION_RECORD` enum constant means the existing
`TraceabilityLink` substrate can target decisions from requirements, ADRs,
risk scenarios, etc. No new link substrate is introduced; that would
fragment the bidirectional lookup surface.

### 4. Standard project-scoped access; no methodology-specific access control

Decision records carry confidential business inputs but no
methodology-specific elevated authority. Access mirrors `Audit` and `Adr`:
authenticated `/api/v1/**` with project-scoped queries.

## Consequences

- Decisions become first-class artifacts that other aggregates can target
  via traceability without renegotiating the link contract.
- The `domain/decisions/` placement avoids the Envers infrastructure clash
  and the workflow-gate string clash.
- `ArtifactType.DECISION_RECORD` lands in the enum-contract inventory and
  propagates to MCP and frontend mirrors via the ADR-034 pipeline.

## Alternatives considered

- **Embed decision records inside `Audit`.** Rejected because audits are
  governed review activities with their own lifecycle (PLANNED → CLOSED).
  Decisions are not lifecycle-managed activities; conflating the two would
  force decision records through audit phase semantics they do not need.
- **Use a free-form note on `ArchitectureDecisionRecord`.** Rejected
  because ADRs document architecture, not run-time analytical decisions
  ("buy vs build for vendor X"). Linking is fine; conflation is not.
