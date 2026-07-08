# In-Loop Control Implementation Preflight

Requirement: GC-GRC-011
Issue: #1124

This is architecture guardrail guidance for implementing the in-loop control
implementation and efficacy-test requirement. It is not an implementation
plan.

## Boundary

GC-GRC-011 is an enforcement rule over existing GRC aggregates. It should not
introduce a second "implemented control" model, test-evidence table, graph
schema, or workflow record format.

- `Control` remains the catalog lifecycle aggregate. The server-side lifecycle
  guard belongs on the service transition path before `IMPLEMENTED` or
  `OPERATIONAL` is persisted.
- `ControlLink` remains the control-owned artifact-link aggregate. CODE
  implementation evidence is a `ControlLink` with `targetType=CODE` and an
  implementation-bearing `linkType`, normally `IMPLEMENTS`.
- `ControlTest` remains the per-execution control test evidence aggregate.
  The graph relationship to the control is already the `control_id` FK projected
  as a `CONTROL_TEST -> CONTROL` `OF_CONTROL` edge.
- `ControlEffectivenessAssessment` remains a rating/assurance judgment. It may
  cite supporting tests, but it is not required just to prove that a control has
  an efficacy test for the status gate.
- `ScopedControlImplementation` is the scoped deployment overlay. Do not treat
  catalog `Control.status` as proof that every scoped implementation is
  operational. If a future requirement needs per-scope operational status, add
  that as a scoped-implementation lifecycle concern rather than overloading the
  catalog control.

## Required Reuse Points

- Put transition enforcement behind `ControlService.transitionStatus`, not in a
  controller, MCP wrapper, workflow step, or frontend check. REST, MCP, and
  future workflow callers already converge there.
- Use `ControlLinkRepository` and `ControlTestRepository` count/existence
  queries for the guard. Do not hydrate full TEXT evidence rows just to decide a
  lifecycle transition.
- Keep request validation in existing request records with Bean Validation and
  semantic validation in services. Invalid transition evidence should throw
  `DomainValidationException` or `ConflictException` through
  `GlobalExceptionHandler` / `ErrorResponse`.
- Keep project scoping through existing repository methods and
  `ProjectService`. Never resolve controls, tests, or first-class targets by
  UUID alone.
- Reuse `GraphTargetResolverService` for internal/external target shape. CODE is
  currently an external-style target identifier; if the CODE identifier grammar
  needs tightening, put it in the shared target-resolution path rather than in
  each caller.
- Extend the existing `gc_control` MCP surface, `gc_query` reads, enum mirrors,
  and OpenAPI-contract tests when API-visible shapes change. Do not add a
  separate GC-GRC-011-specific MCP tool for normal control writes.
- Extend the `gc_test_quality_review` MCP prompt/rubric when test-quality
  recognition changes. The MCP tool is the trust boundary; skill prose alone is
  not enforcement.

## Efficacy Test Semantics

For this requirement, an efficacy test is not a test that only proves the graph
row, status row, or link row exists. It must fail when the control it protects
is removed, bypassed, or materially weakened.

Acceptable evidence can include a backend/service/controller/frontend/MCP test
that drives the protected behavior through the relevant boundary and asserts
the control effect. A `ControlTest` row links that efficacy evidence to the
control in the project graph; the row's `testSteps`, `expectedResults`, and
`actualResults` should name the behavior under test rather than merely naming a
file or test method.

Tests that only assert any of the following are existence tests and should not
satisfy GC-GRC-011:

- a `ControlTest` row exists;
- a CODE link exists;
- a control can transition to `IMPLEMENTED` or `OPERATIONAL`;
- a snapshot or string contains the control UID;
- a mock was called, with no assertion on the behavior the control protects.

## Cross-Cutting Layers

- Security: every REST path stays under `/api/v1/**` and the shared
  `ApiPathMatrix`; no route-local auth, caller-supplied actor, privileged GitHub
  call, shell-out, or token-bearing argv path is needed.
- Secret handling and OS exposure: this requirement should require no new
  secret, environment binding, subprocess, scanner invocation, or network client.
  Future external verification adapters must use `@ConfigurationProperties`
  boundaries and keep secrets out of logs, argv, and error envelopes.
- Error envelope: status-gate failures may name stable control/test/link ids,
  UIDs, target types, and missing evidence kinds. They must not echo full test
  steps, actual results, raw diff content, file contents, tokens, or request
  bodies.
- Audit and observability: Envers plus `ActorFilter` / `ActorHolder` provide
  actor provenance. Logs should be low-cardinality lifecycle events and must not
  contain raw evidence payloads.
- Persistence and graph: JPA/Flyway remain the source of truth. Graph changes
  flow through `GraphProjectionContributor`; do not write AGE rows directly.
- Workflow records: issue-thread records remain durable via ADR-029 MCP tools.
  GC-GRC-011 should strengthen the existing GRC completion assertion path; it
  should not create a parallel final-report or screening-marker family.

## Extensibility Seams

The immediate seam is a domain helper or service rule that evaluates whether a
control has implementation CODE linkage and efficacy-test linkage for a target
status. Keep the rule parameterized by target status and owner kind so GC-GRC-012
can reuse it for completion coverage and a future scoped-implementation
lifecycle can reuse the same concept without re-editing every caller.

If CODE targets later become first-class repository artifacts, promote the target
through the established one-place path: target enum, `GraphTargetResolverService`,
graph projection, MCP/frontend mirrors, API docs, and tests. Do not create a
feature-local code-reference parser.

## Gotchas And Anti-Patterns

- Do not count `ControlStatus.OPERATIONAL` as evidence that the control is
  effective; status is lifecycle, not effectiveness.
- Do not count `Control.effectiveness` or arbitrary methodology-factor JSON as
  the source of truth for implementation or efficacy evidence.
- Do not conflate `VerificationResult`, `Observation`, `EvidenceArtifact`, or
  `ControlEffectivenessAssessment` with the required efficacy-test row.
- Do not make workflow prose the only guard. Tool contracts and service-side
  checks must carry the invariant.
- Do not add duplicate exception classes, DTO schemas, target validators, graph
  writers, MCP tools, or frontend-only validation.
- Do not add a new abstraction unless the same rule has at least the concrete
  reuse pressure from `ControlService.transitionStatus` and the GRC completion
  assertion.

## Non-Goals

- No implementation of GC-GRC-011 in this note.
- No new control lifecycle states.
- No new graph node type for code artifacts.
- No automatic claim that all scoped control implementations are operational.
- No DAST/runtime instrumentation or external scanner integration.
- No replacement of ADR-058's derivation-backed GRC engine, ADR-039's control
  verification aggregates, or ADR-052's scoped-control mapping boundary.
