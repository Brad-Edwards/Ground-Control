# Source-Gate Operating Controls

This document is category-level architecture guidance for operating controls
whose gate is `before-source`. It records boundaries and incumbents only; it is
not an implementation plan.

## Scope

Source-gate controls protect the traceability and evidence surface before a
change starts writing product source. They apply to `/implement` runs, issue
thread records, Ground Control links, quality gates, GRC screening, and the
repository policy layer.

For the `compliance-requirement-traceability` control, the operating objective
is:

> Product behavior must stay traceable to requirement, ADR, issue, test, and
> evidence records for compliance-relevant surfaces.

## Source of Truth

The control row names four sources of truth. Each maps to a concrete, existing
Ground Control mechanism, not a parallel compliance-traceability system:

- **Ground Control links**: requirement-to-artifact traceability is the existing
  `TraceabilityLink` model (`ArtifactType` plus `LinkType`, owned by
  `TraceabilityService`), reachable through `gc_get_traceability_by_artifact` and
  `GET /api/v1/requirements/traceability/by-artifact`, and audited at the
  completion gate by `gc_assert_traceability_reconciled`.
- **PR traceability**: pull-request bodies are rendered deterministically by
  `gc_render_pr_body`, which carries the requirement and traceability section, and
  PR state is reflected in the graph (GC-D002 PR-requirement linking).
- **Test evidence**: `TESTS` links, the server-side project quality gates
  (`QualityGateService.evaluate`, synced from `tools/ground_control/policy.json`),
  and `EvidenceArtifact` records carry test evidence for a requirement.
- **Assessment evidence index**: `Control`, `ControlLink`, `ControlTest`,
  `ControlEffectivenessAssessment`, and `EvidenceArtifact`, surfaced through the
  evidence and state workspace, index the assessment evidence.

Durable workflow state is the GitHub issue thread per ADR-029, rendered through
deterministic MCP tools rather than ad hoc comments. GRC screening and
reconciliation follow ADR-057 v1 records and the ADR-058 derivation-backed target
contract. This durable record is itself kept present and well-formed by
`tools/policy/check_operating_controls.py` (run in `make policy` and CI), tracing
back to requirement GC-O002 (Self-Referential Traceability).

## Guardrails

- Use `DOCUMENTS` links for design, ADR, issue, policy, or evidence records
  that justify work before source implements behavior.
- Use `IMPLEMENTS` only for delivered behavior, preserving the existing
  `ACTIVE` requirement invariant enforced by `TraceabilityService`.
- Use `TESTS` when an `ACTIVE` requirement has testable implementation
  artifacts on the executable surfaces covered by the traceability assertion.
- Keep reverse artifact lookups project-scoped through
  `GET /api/v1/requirements/traceability/by-artifact` or
  `gc_get_traceability_by_artifact`.
- Keep control links separate from requirement traceability: control links prove
  control implementation or evidence; traceability links prove requirement
  coverage.
- Keep evidence records separate from mutation audit. Envers explains who
  changed rows; evidence artifacts, control tests, assessments, and
  traceability links explain what supports the compliance claim.
- Keep privileged GitHub writes in MCP durable-record tools. Agent prose,
  telemetry, PR body summaries, or local files are not substitutes for the
  issue-thread records.

## Cross-Cutting Layers

- Security: REST paths remain under `/api/v1/**` and pass the existing Spring
  Security chains, authorization matrix, and `ActorFilter`.
- Validation: request DTOs own shape checks; services own project scoping,
  link legality, duplicate checks, lifecycle transitions, and same-project
  target validation.
- Error handling: backend failures use `GroundControlException` subclasses via
  `GlobalExceptionHandler` and `ErrorResponse`; MCP tools return structured
  refusal envelopes.
- Logging and audit: use low-cardinality SLF4J events and existing actor/MDC
  provenance; never log tokens, raw evidence payloads, prompts, diffs, or full
  command output.
- Configuration and OS exposure: do not add secrets, env bindings, subprocess
  calls, argv-visible tokens, local durable state, or filesystem scanners for a
  source-gate traceability control unless a later ADR creates that boundary.

## Non-Goals

- No parallel traceability table, control-row schema, workflow database, or
  local marker file.
- No duplicate link validator, exception hierarchy, error envelope, security
  filter, audit writer, graph materializer, or quality-gate evaluator.
- No prompt-only gate in `skills/implement/SKILL.md` for a rule that the MCP or
  policy layer cannot enforce.
- No broad source scan, unscoped GitHub issue lookup, raw database read, or
  direct AGE write from a workflow tool.

## Extension Point

Add future source-gate controls as bounded sections or sibling category docs
that name their source of truth, existing incumbents, evidence records, and
non-goals. Add a new backend aggregate, workflow stage, policy check, or MCP
tool only when the control has a distinct lifecycle, query, or enforcement need
that the existing traceability, quality-gate, GRC, and evidence contracts cannot
represent.
