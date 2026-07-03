# ADR-058: Derivation-First Continuous GRC

## Status

accepted

## Date

2026-06-12

## Context

The continuous secure-by-design GRC program (GC-GRC-001..033, wave 7)
changes how security work happens in Ground Control consumer repositories.
ADR-057 added the first enforceable `/implement` screening record, but its v1
contract still depends on an agent selecting a verdict and still treats an
empty baseline as a recorded `no_baseline` declination that can pass the
workflow.

That is not sufficient for the next GRC wave. The target system must derive a
security-relevant system model from the changed repository, enumerate threats
and controls deterministically, use LLM judgment only where judgment adds
value, enforce coverage mechanically, and keep the model current as code
changes. This needs a binding program ADR before the implementation issues
land so the wave does not split into unrelated scanners, prompt instructions,
repo-local diagrams, or workflow-specific one-offs.

Existing Ground Control decisions provide the substrate:

- ADR-014 defines the verifier-adapter pattern that the derivation-adapter port
  mirrors.
- ADR-024, ADR-038, ADR-039, ADR-045, ADR-048, and ADR-052 define graph-native
  GRC aggregates and evidence/control/risk boundaries.
- ADR-027 keeps repo-specific workflow/config context in `.ground-control.yaml`
  and `gc_get_repo_ground_control_context`.
- ADR-029 keeps workflow records on the GitHub issue thread.
- ADR-036 provides per-step routing, telemetry, and deterministic durable-record
  tool surfaces.
- ADR-057 provides the v1 per-run GRC screening gate that this ADR amends.

## Decision

Adopt a derivation-first continuous GRC architecture with one engine and two
entry points:

```text
derive -> enumerate -> judge -> enforce -> maintain
```

The shared engine powers both the in-loop `/implement` gate and the standalone
assessment lane. The two entry points differ in scope and scheduling, not in
model semantics.

### 1. Derive repository facts through adapter ports

Ground Control will derive system-model facts from code, configuration,
pipelines, dependencies, and declared project metadata through a
language/surface-agnostic derivation adapter port (GC-GRC-001). The port mirrors
ADR-014's verifier-adapter pattern: domain contracts define normalized input and
output; concrete tools live behind adapters.

The first adapter families are CodeQL (GC-GRC-002), IaC and CI/CD pipelines
(GC-GRC-003), Semgrep and language-breadth analyzers (GC-GRC-027), secrets
(GC-GRC-028), and software-composition/supply chain analysis (GC-GRC-029).
Adapters emit normalized facts: components, trust boundaries, data flows, entry
points, call/data-flow reachability, taint paths, secret usages, dependency
facts, external interactions, and data-classification hints.

Every fact carries provenance: tool, tool version, rule/query-pack version,
commit SHA, timestamp, and scope. Diff-scoped derivation and full-scope
derivation use the same interface, with incremental caching and correct
invalidation for routine `/implement` use (GC-GRC-033).

Adapter absence is never silent. The coverage matrix records which surfaces
were derivable, which were declared manually, and which remain explicit capture
limits (GC-GRC-025).

### 2. Model boundaries and architecture server-side

The modeling unit is the architectural boundary, not a file or an arbitrary
agent summary (GC-GRC-004). Boundaries can be derived by adapters or declared
in the repository GRC configuration surface (GC-GRC-023); both routes merge
into one versioned boundary set.

Derived facts are persisted into a graph-native architecture model aggregate
in Ground Control (GC-GRC-005). The model represents DFD semantics: components,
processes, stores, external entities, data flows, trust boundaries, and data
classifications. Model elements are graph nodes linkable from threat models,
risk scenarios, controls, assets, evidence, requirements, code, and issues.

Data classification uses a project-scoped lattice (GC-GRC-006). Lattice policy
turns sensitive data flow from a subjective review question into a checkable
property: a sensitive flow into a lower-trust sink is a derivable finding.

Ground Control's database is authoritative for architecture models, DFDs,
derived facts, threats, risks, controls, assessments, drift state, dispositions,
and coverage state (GC-GRC-026). Repository mirroring is opt-in per artifact
class, default off, and must carry generation markers tying any mirror back to
the authoritative graph version. Agents retrieve GRC context through graph and
MCP/REST reads, including code-keyed reverse lookup by artifact or boundary
(GC-GRC-031).

### 3. Enumerate threats, controls, and attack paths deterministically

Threat enumeration is rule-pack logic over the derived architecture model, not
LLM generation (GC-GRC-007). A STRIDE-per-element baseline applies to processes,
stores, flows, external entities, and boundary crossings. Category rule packs
cover deployment/pipeline, authentication/authorization, secret handling,
untrusted input, data egress, cryptographic surfaces, and supply chain surfaces.
Every candidate carries the producing rule and matched facts.

Control identification is deterministic mapping from threat category to control
objective to candidate controls, using installed control packs and existing
project controls (GC-GRC-008). Gaps where no control matches are explicit design
work, not dropped candidates.

Attack-path analysis chains element-level threats and boundary crossings into
reachable paths from entry points to high-value assets and sensitive data
stores (GC-GRC-030). Sensitive data flows are checked against the lattice
(GC-GRC-020), and quantitative information-flow metrics can feed risk
methodology inputs where analyzers support them (GC-GRC-022).

### 4. Narrow LLM judgment to curation and adversarial completeness

LLM judgment is useful after derivation and deterministic enumeration have set
the floor. It must not be the floor.

The LLM/agent role is limited to:

- confirming, discarding with rationale, or augmenting deterministic candidates;
- adversarial completeness review over derived facts, the proposed model, and
  the diff (GC-GRC-014);
- planning and implementing the controls and risk-treatment work that the
  gate requires.

The in-loop plan must list GRC deliverables as first-class plan items before
implementation begins (GC-GRC-010). Threats confirmed during a change map to
risk scenarios and methodology-scoped assessments in the same run where the
scope is change-sized (GC-GRC-021). Declining identified GRC work requires an
authorized disposition materialized in the issue thread and graph (GC-GRC-015).

### 5. Enforce mechanically at screening, plan, and completion

ADR-057's v1 `security_relevant` / `not_security_relevant` / `no_baseline`
record is superseded in part by a derivation-backed screening contract
(GC-GRC-009).

The screening gate computes three sets from derived facts:

- `impact_set`: existing threats, risks, controls, architecture-model elements,
  boundaries, and code links touched by the change.
- `gap_set`: security-relevant touched surfaces with no model coverage, no
  threat coverage, no control coverage, or no derivation coverage.
- `stale_set`: linked GRC entities whose underlying code, boundary, rule pack,
  query pack, lattice, or declared model changed.

There is no passing `no_baseline` verdict in the target contract. An empty or
absent baseline creates a `gap_set` covering the touched security-relevant
surface, scoped to the changed boundaries. That gap set must be modeled,
controlled, or dispositioned before completion.

The GC-O012 issue-thread screening record evolves from `gc.implement.grc-screening/v1`
to a derivation-backed record contract. The record remains durable on the issue
thread per ADR-029, but it adds computed sets, candidate threats, candidate
controls, derivation provenance, coverage-matrix declinations, and rule-pack
versions. `not_security_relevant` becomes a derived empty-impact/empty-gap
result, not an agent assertion. Existing v1 records remain historical records;
new implementation work should target the computed contract.

The plan gate realizes GC-GRC-010: `gc_post_implementation_plan` requires both a
`preflight` and a `grc_screening` marker, and for a `security_relevant` screening
record it requires structured `grc_deliverables` (kind + source-set target)
covering every `gap_set` surface and `stale_set` entity. Coverage is kind-aware: a
gap surface is closed by a threat/risk/control deliverable and a stale entity by a
stale-refresh deliverable. Deliverables are modeled by kind, not scraped from plan
prose, and are rendered into the plan comment as an authoritative
`gc:grc-deliverables-data` machine block: the durable plan-to-completion trace the
completion gate reads. In-scope GRC work cannot be deferred to a follow-up issue.
A disposition is the no-defer rule's single relief valve; because this MCP surface
has no per-user authentication (a caller-supplied authorizer is self-attested), a
disposition is honored only under the audited `override` escalation, and
GC-GRC-015 supplies the graph-verified, drift-aware per-entity disposition. Candidate threats/controls
(GC-GRC-007/008) are surfaced as suggestions but never auto-counted as selected or
implemented controls.

The completion gate becomes a blocking GRC coverage assertion (GC-GRC-012): each
security-relevant touched surface must have an active threat-model entry, a
control at IMPLEMENTED or OPERATIONAL with code and efficacy-test linkage, or an
authorized disposition. Controls identified for a change ship in that change
with efficacy tests (GC-GRC-011). Knock-on graph propagation flags related
entities that need reassessment (GC-GRC-013).

### 6. Maintain through a drift control loop and assessment lane

The standalone assessment lane is user-directed in scope and shares the same
engine as the in-loop gate (GC-GRC-016). Scope can be whole project, paths,
packages, boundaries, assets, named threat/risk sets, or the current drift set.
Modes include model, reassess, and re-screen.

The lane is the baseline bootstrap path for existing repositories (GC-GRC-018).
A full-scope model run derives the whole architecture model, enumerates
candidate threats and controls, proposes risk scenarios, and reconciles the
reviewed output into the graph.

Assessments are schedulable and event-triggered (GC-GRC-017). Triggers include
control-pack updates, methodology changes, rule/query-pack pin changes, KRI
signals, reassessment dates, and drift threshold breaches.

Drift is the computed error between freshly re-derived facts and the recorded
architecture/GRC graph (GC-GRC-019). Drift is computed per boundary and entity
class. Workspaces expose architecture, coverage, and drift state for review
(GC-GRC-024). Compliance posture and evidence export are generated from the
same graph and evidence aggregates (GC-GRC-032).

### 7. Runtime and dynamic analysis are out of scope

Dynamic analysis, DAST, and runtime instrumentation are not part of this
build-time derivation program. Runtime evidence belongs to the ADR-014
verifier-adapter/runtime-evidence world and can feed the same graph through a
separate evidence adapter. This ADR intentionally scopes the first program to
build-time repository derivation, deterministic enumeration, and graph-native
enforcement.

## Requirement coverage

| Program area | Requirements |
|--------------|--------------|
| Derivation foundation | GC-GRC-001, GC-GRC-002, GC-GRC-003, GC-GRC-027, GC-GRC-028, GC-GRC-029, GC-GRC-033 |
| Architecture model and boundaries | GC-GRC-004, GC-GRC-005, GC-GRC-006, GC-GRC-023, GC-GRC-025, GC-GRC-026, GC-GRC-031 |
| Enumeration and judgment | GC-GRC-007, GC-GRC-008, GC-GRC-014, GC-GRC-020, GC-GRC-022, GC-GRC-030 |
| In-loop enforcement | GC-GRC-009, GC-GRC-010, GC-GRC-011, GC-GRC-012, GC-GRC-013, GC-GRC-015, GC-GRC-021 |
| Lane, maintenance, and audit output | GC-GRC-016, GC-GRC-017, GC-GRC-018, GC-GRC-019, GC-GRC-024, GC-GRC-032 |

This ADR intentionally references the full GC-GRC-001..033 program set. The
build order can remain dependency-led rather than UID-ordered.

## Rationale

Derivation-first GRC makes the security model reproducible. The workflow can
explain why a change is security-relevant by pointing at derived facts,
rule-pack matches, and graph coverage gaps instead of an agent's subjective
classification.

Deterministic enumeration gives Ground Control a testable floor for threat and
control coverage. LLM review remains valuable, but it checks completeness over
facts and model elements rather than inventing the model from prose.

One engine with two entry points prevents drift between the implementation loop
and standalone assessments. The lane bootstraps and maintains the baseline; the
loop consumes the same model and blocks only on the changed surface and its
knock-on effects.

## Consequences

### Positive

- Security work becomes inherent to the work item: a relevant change cannot
  pass without modeled threats, selected controls, implemented controls,
  efficacy tests, or an authorized disposition.
- The LLM is no longer the primary threat generator. Deterministic derivation
  and rule packs create a reproducible floor, while adversarial review checks
  completeness above that floor.
- Empty baselines stop being a workflow pass. The assessment lane gives teams a
  bootstrap path, and in-loop runs create scoped gap sets until the baseline is
  built.
- Sensitive GRC artifacts stay server-side by default, which is safe for public
  repositories and consistent with existing Ground Control aggregate patterns.
- The same graph that gates implementation can produce workspaces, drift
  signals, and compliance evidence.

### Negative

- The program is larger than ADR-057's v1 gate: it requires adapters, an
  architecture-model aggregate, rule packs, coverage assertions, a lane
  executor, and workspace surfaces.
- Cold-start repositories can see more blocking gaps until a baseline bootstrap
  run has been reviewed.
- Deterministic derivation adds wall-clock cost to `/implement`; GC-GRC-033
  makes incremental caching and telemetry a first-order requirement.

### Risks

| Risk | Mitigation |
|------|------------|
| Rule packs encode an incomplete threat model | Rule-pack provenance, adversarial completeness review, and drift metrics show where the floor is incomplete. |
| Empty baseline blocks too much work | Gap sets are scoped to touched boundaries, and the assessment lane bootstraps the baseline. |
| Derived facts expose sensitive architecture details in public repos | Server-side storage is the default, and repo mirroring is opt-in with public-repo warnings. |
| Adapter coverage creates false confidence | The coverage matrix records capture limits and treats undeclared underivable surfaces as gaps. |
| LLM review re-expands into generation | The record contract requires findings to reference facts, model elements, or diff hunks. |

## Alternatives Considered

### Keep ADR-057 v1 as the long-term gate

Rejected. ADR-057 v1 is valuable as the first mechanical screening record, but
it still records a self-certified verdict and lets empty baselines pass as
`no_baseline`. GC-GRC-009 and GC-GRC-012 require computed classification and
blocking coverage.

### Let the LLM generate the threat model directly

Rejected. Generated threat models are not reproducible, cannot explain coverage
against a changed surface, and cannot prove that a baseline absence is a gap.
The LLM remains useful for curation and adversarial review after derivation and
enumeration.

### Store GRC artifacts in the analyzed repository

Rejected as the default. Some repositories are public, and threat models, data
flows, secrets facts, and attack paths are sensitive. Ground Control stores the
authoritative graph server-side. Mirroring rendered artifacts is opt-in.

### Build only the in-loop gate

Rejected. Existing codebases need a bootstrap and reassessment path. The
assessment lane and the in-loop gate must share one engine so they cannot drift
in model semantics.

### Include DAST and runtime instrumentation in this program

Rejected for this build-time ADR. Runtime evidence is valuable, but it belongs
to the verifier/runtime-evidence adapter family and can be joined into the same
graph without expanding the build-time derivation scope.

## Related ADRs

- ADR-014: Pluggable Verification Architecture
- ADR-024: Threat Model Entry Boundary
- ADR-027: Agent-Neutral Implement Workflow Packaging
- ADR-029: Issue-Thread Gate Model
- ADR-036: Per-Step Model Routing, Durable-Record Tool Surfaces, and Step Telemetry
- ADR-045: Evidence Derivation and Temporal State History
- ADR-052: Risk-Control Mapping Aggregate
- ADR-057: Per-run GRC screening gate in `/implement`
