# ADR-081: Temporal Dev Workflow and Console Program

## Status

Accepted

## Date

2026-07-03

## Context

The direction is decided but scattered. GC-O009 (MUST, wave 3) specifies
Temporal as the workflow orchestration engine, ADR-028 (accepted 2026-05-03)
defines the orchestration boundary, and issue #529 holds the engine plan. Zero
engine code exists: no Temporal SDK dependency, no containers in the dev or
production compose files, no worker process. The workflow-run surface shipped
by ADR-061 is a correlation/projection facet, documented in its own Javadoc as
"not the workflow engine."

Milestone 17 ("Temporal dev workflow & SaaS console") extends the engine work
with the surfaces needed to run the entire development workflow - for every
project this instance manages - through the Ground Control web console:

- GC-O014 Contract-First Development Surface (issue #1275)
- GC-P024 User, Group, and Role Administration (issue #1282)
- GC-P025 Runtime Metrics and Distributed Tracing (issue #1285)
- GC-Q015 Console Shell and Design System (issue #1283)
- GC-Q016 Workflow Operations and Agent Interaction Console (issue #1284)

Without a binding program ADR, this wave has the same failure mode ADR-058
called out for the GRC wave: the work splits into disconnected one-offs, and
load-bearing constraints (the ADR-029 gate model, the ADR-028 boundary, the
no-tenancy line) erode issue by issue.

Two corrections are overdue and belong to this ADR:

- GC-O009's statement predates ADR-029. It describes "plan approval and merge
  approval implemented as Temporal signals." ADR-028 explicitly flags this
  wording as stale and forbids driving implementation from it.
- GC-O009's statement describes "multi-tenant isolation ... via Temporal
  namespaces or workflow-ID partitioning." ADR-028 decided a single namespace
  partitioned by project, with tenant-to-namespace mapping deferred to a future
  tenancy ADR.

## Decision

Adopt milestone 17 as a program with a dependency-led build order, a
contract-first mandate, and a locked decision set. Implementation issues in
the milestone are bound by this ADR.

### 1. Build order

The engine track is sequential; the identity, console, and instrumentation
tracks run alongside it once their design ADRs land. Contract publication
(per ADR-082) precedes implementation within every phase.

| Phase | Work | Issue |
|-------|------|-------|
| 0 | Contract surface foundation (GC-O014) | #1275 |
| 1 | Temporal infrastructure: server, visibility, worker topology, deploy fit | #1276 |
| 2 | Deterministic core workflow: typed activities, replay tests | #1277 |
| 3 | Workflow control surface: REST and MCP start/status/signal | #1278 |
| 4 | Human gates: merge observation, authorized operator signals | #1279 |
| 5 | LLM activities and provider boundary | #1280 |
| 6 | Transition bridge: /implement drives and observes Temporal runs | #1281 |
| 7 | Multi-project rollout | #1286 |
| P (parallel) | Identity: users, groups, roles as data (GC-P024) | #1282 |
| P (parallel) | Console shell and design system (GC-Q015) | #1283 |
| P (parallel, after phases 3-4 and identity) | Workflow operations console (GC-Q016) | #1284 |
| P (parallel, starts with phase 1) | Metrics and distributed tracing (GC-P025) | #1285 |

Ordering constraints that are load-bearing:

- Phase 0 precedes phase 2: workflow and activity input/output records are
  versioned contracts before activity implementation lands (ADR-028
  requirement, mechanized by GC-O014/ADR-082).
- Identity (GC-P024) precedes console gate actions (GC-Q016 clause b):
  operator signals require attributable principals with explicit gate
  authority. Until GC-P024 lands, signal endpoints require `ROLE_ADMIN`.
- The bridge (phase 6) precedes any removal of skill-lane enforcement; see
  the cutover model below.

### 2. Locked decisions

1. **Temporal is an orchestration adapter, not a second domain model.**
   ADR-028 governs in full: Temporal history is the source of truth for
   execution progress; PostgreSQL holds configuration and correlation records
   only; no parallel workflow state machine; `domain/` never imports the
   Temporal SDK; REST/MCP/UI visibility reads come from Temporal Visibility
   plus correlation data.
2. **The one-human-touchpoint contract is preserved.** PR merge remains the
   single synchronous human gate (ADR-029). GitHub's merge action is the
   authoritative event, observed by the workflow via webhook or polling,
   never modeled as a Temporal signal. Console gate actions are limited to
   the explicit operator signal set the workflow contract already defines
   (cancel, retry-from, review-cap disposition/override under GC-O007's
   rules). No synchronous plan-approval gate is reintroduced by any phase,
   including the console. A policy test asserts the implemented gate set
   equals the contract's.
3. **Contract-first is a phase precondition, not a cleanup.** Every phase
   publishes its REST, MCP, and workflow/activity payload contracts under the
   ADR-082 surface with drift and breaking gates green before the phase's
   implementation merges.
4. **Identity lands without tenancy.** GC-P024 builds users, groups, roles,
   and project-access grants as product data at the V059 seam. Project
   scoping remains distinct from tenant isolation (ADR-016, ADR-028);
   tenant-to-Temporal-namespace mapping requires its own future ADR and is
   out of scope for this program. GC-P018/GC-P020 (milestone 5) build on,
   and are not implemented by, this program.
5. **Temporal Web and gRPC endpoints are infrastructure surfaces.** They are
   never exposed as the product UI and never relied on as an authorization
   boundary (ADR-028). The console consumes only the product control surface
   (phase 3), which enforces project scoping, role-based authorization, and
   audit.
6. **`.ground-control.yaml` stays the only workflow configuration schema.**
   Temporal consumes the ADR-027 configuration shape through the existing
   parser boundary. No second workflow DSL, no activity lists parsed from
   prose (ADR-028).
7. **Deterministic/LLM separation is structural.** Deterministic activities
   (issue resolution, git operations, traceability reconciliation, status
   transitions, gate evaluation) carry no LLM provider dependency,
   enforced by architecture rules; LLM-backed activities route through the
   provider port with the ADR-028 redaction constraints (no prompts,
   completions, or secrets in workflow history, Search Attributes,
   responses, audit rows, or logs).

### 3. Skill-lane cutover model

The `/implement` skill lane (ADR-021, ADR-027, ADR-029, ADR-036) remains the
production workflow while the engine matures. Ownership of a workflow phase
transfers from the skill lane to Temporal only when all of the following hold:

1. The parity harness (issue #1281) is green for that phase: the same issue
   driven through both lanes produces the same durable records and gate
   outcomes.
2. The MCP-side issue-thread marker enforcement for that phase remains
   authoritative up to the moment of transfer; the bridge holds no
   independent counters, phase state, or gate rules (ADR-028's second-engine
   prohibition).
3. The transfer is recorded as a dated amendment to ADR-021 and ADR-029
   naming the phase, the owning Temporal workflow/activity contracts, and
   the enforcement that moved server-side.

Until a phase transfers, its skill-lane enforcement is not weakened. ADR-021,
ADR-027, and ADR-036 carry amendment notes pointing at this cutover model;
their contracts are otherwise unchanged by this ADR.

### 4. GC-O009 statement amendment

GC-O009's statement is amended (in Ground Control, 2026-07-03) to match the
accepted decisions:

- Clause (b) now reads that the workflow has exactly one synchronous human
  gate (PR merge) observed from GitHub as the authoritative event, and that
  operator signals are explicit, contract-versioned, authorized, and audited
  - replacing "plan approval and merge approval implemented as Temporal
  signals."
- Clause (d) now reads project-scoped isolation via workflow-ID and
  Search-Attribute partitioning within a single namespace, with
  tenant-to-namespace mapping deferred to a future tenancy ADR - replacing
  "multi-tenant isolation ... via Temporal namespaces or workflow-ID
  partitioning."
- The opening sentence's "multi-tenant workflow infrastructure" becomes
  "project-scoped workflow infrastructure."

Issue #529's prose predates ADR-029 in the same places; where it conflicts
with this ADR, this ADR governs.

## Requirement coverage

| Program area | Requirements | Issues |
|--------------|--------------|--------|
| Engine | GC-O009 | #529, #1276, #1277, #1278, #1279, #1280, #1281, #1286 |
| Contract surface | GC-O014 | #1275 (architecture: ADR-082) |
| Identity | GC-P024 | #1282 (design: #1273) |
| Instrumentation | GC-P025 | #1285 |
| Console | GC-Q015, GC-Q016 | #1283, #1284 (design: #1274) |

## Rationale

The program-ADR pattern worked for the GRC wave (ADR-058): a single binding
record of build order and locked decisions keeps a multi-issue program from
drifting into unrelated one-offs, and gives every implementation issue a
stable authority to check its plan against.

The contract-first mandate is sequenced before the engine because ADR-028
already requires workflow and activity records to be versioned API contracts;
building the contract machinery after the activities would retrofit contracts
onto shipped payloads - the debt pattern GC-O014 exists to prevent.

The gate-model corrections are folded in here rather than left to the engine
issues because ADR-028 explicitly warns that GC-O009's stale wording must not
drive implementation; the correction has to land before phase 2.

## Consequences

### Positive

- Every milestone-17 issue plans against one authority for ordering, gate
  semantics, tenancy scope, and contract obligations.
- GC-O009's statement stops contradicting ADR-028/ADR-029, closing the
  known stale-wording trap.
- The skill lane keeps shipping during the transition, and each ownership
  transfer is an auditable, reversible, per-phase event.

### Negative

- The program ADR adds coordination weight: implementation issues must check
  their plans against it, and per-phase transfers require ADR amendments.
- Running two lanes (skill + Temporal) through the bridge period costs
  parity-harness maintenance until cutover completes.

### Risks

| Risk | Mitigation |
|------|------------|
| The console quietly grows gate actions beyond the contract's operator set | Locked decision 2 plus a policy test asserting the implemented gate set equals the workflow contract's. |
| The bridge accretes phase state and becomes a second engine | Locked decision and cutover condition 2; ADR-028's prohibition is restated as a review criterion on #1281. |
| Project scoping gets marketed or coded as tenant isolation | Locked decision 4; tenancy work stays in milestone 5 behind its own ADR. |
| Contract-first is skipped under schedule pressure | Phase completion criterion: drift and breaking gates green is part of each phase's acceptance, checked at PR review. |

## Alternatives Considered

### Big-bang replacement of the skill lane

Rejected. The skill lane is the production workflow for this repo and its
consumers; replacing it without per-phase parity evidence would trade a
working, evolving lane for an unproven one. The bridge and cutover model keep
both lanes honest.

### Console first, engine later

Rejected. Without the engine there is nothing for gate actions to signal; the
console would bind to the telemetry projection (ADR-061) and recreate the
parallel-state-machine failure ADR-028 forbids.

### Treat Temporal Web as the workflow UI

Rejected by ADR-028 and restated here: it has no project scoping, no
role-based authorization, no audit trail, and would bypass the product
boundary.

### Keep the CLI-only workflow and skip the console program

Rejected. GC-O009(c) already requires web UI visibility, and operating all
projects' development requires gate actions and run control by more than one
authenticated principal - which the CLI-per-operator model cannot express.

## Non-Goals

- Implementing SaaS tenancy, subscription plans, or tenant administration
  (GC-P006/P018/P019/P020, GC-Q014 - milestone 5).
- A new workflow DSL or marketplace or dynamically loaded activities (ADR-023,
  ADR-028 non-goals restated).
- Replacing quality gates, traceability services, or the `/api/v1/**`
  security and error contract.
- Changing the GC-O007 gate semantics; this program preserves them and only
  relocates enforcement per the cutover model.

## Related Requirements

- GC-O009 Workflow Orchestration via Temporal
- GC-O014 Contract-First Development Surface
- GC-P024 User, Group, and Role Administration
- GC-P025 Runtime Metrics and Distributed Tracing
- GC-Q015 Console Shell and Design System
- GC-Q016 Workflow Operations and Agent Interaction Console
- GC-O007 Gated Agentic Development Loop (contract preserved)

## Related ADRs

- ADR-016 Project Scoping
- ADR-021 Gated Agentic Development Loop (amendment note added)
- ADR-026 REST API Access Control
- ADR-027 Agent-Neutral Implement Workflow Packaging (amendment note added)
- ADR-028 Temporal Workflow Orchestration Boundary (governs the engine)
- ADR-029 Issue-Thread Gate Model
- ADR-036 Per-Step Model Routing, Durable-Record Tool Surfaces, and Step
  Telemetry (amendment note added)
- ADR-037 Browser Session Access Control
- ADR-058 Derivation-First Continuous GRC (program-ADR pattern)
- ADR-061 Workflow-Run Telemetry & Economics Reporting Surface
- ADR-082 Contract Surface Architecture and Enforcement Gates (companion)
