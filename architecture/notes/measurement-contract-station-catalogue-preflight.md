# Measurement Contract And Station Catalogue Preflight

Issue: #1438
Requirement: none

This note records the architecture disposition for publishing ADR-090's
versioned measurement contract and authoritative station catalogue. It is not
an implementation plan.

## Boundary Decisions

- Publish the contract as a versioned ADR-082 artifact under `contracts/schemas/`;
  do not leave station identity, outcome vocabularies, or aliasing rules in ADR
  prose alone.
- Treat the contract as the shared machine-readable surface for emitters and
  consumers, not as a new runtime aggregate, reporting table, workflow engine,
  dashboard schema, or generic measurement bus.
- Make one catalogue authoritative for station identity. ADR-061 `phase`,
  ADR-036 SKILL step ids, issue-thread `gc:phase` markers, MCP action labels,
  and UI wording are inputs and aliases, never competing identities.
- Keep the three ADR-090 outcome axes structurally distinct in the contract:
  operation outcome, station result, and run outcome/state must not share one
  enum, one field, or one alias map.
- Keep the measurement contract additive to existing owners. `WorkflowRun`,
  `WorkflowPhaseEvent`, MCP tool-usage telemetry, local ADR-036 JSONL, and
  future bounded OTel attributes map into the contract; the contract does not
  replace their storage or authority.

## Canonical Incumbents To Reuse

- ADR-082 governs versioned contract publication, breaking-change declarations,
  and `contracts/CHANGES.md`. Reuse that surface exactly; do not create a
  measurement-only changelog, version registry, or schema home.
- `contracts/schemas/README.md` and the existing
  `workflow-run-record.v1.schema.json` establish the repo's JSON Schema
  conventions, especially `x-ground-control-invariants`.
- ADR-061's reporting model and write path are the existing semantic home for
  run identity, run state, phase/station attempts, project scoping, and durable
  reporting facts. Extend that path when runtime code later needs the catalogue;
  do not add a parallel measurement aggregate.
- ADR-036 already defines provider-neutral capability tier semantics and the
  distinction between SKILL step ids and stable workflow-stage names. Reuse
  those meanings instead of re-describing them in a second vocabulary table.
- MCP telemetry (`mcp/ground-control/telemetry.js`) already demonstrates the
  closed-shape, fail-open capture rule for operation outcome. That axis should
  map into the contract as-is rather than being renamed into station result.

## Contract Shape Guardrails

- Put the station catalogue in its own versioned contract artifact or a clearly
  separated schema definition inside the published measurement contract surface.
  The key requirement is that `station_id` authority and alias resolution are
  data, not code comments and not scattered string constants.
- Model aliases explicitly by source kind. At minimum distinguish stable machine
  aliases such as ADR-061 phase ids, ADR-036 step ids, and issue-thread marker
  values from human display labels so a UI rename never becomes a breaking
  identity change.
- Keep bounded vocabularies closed and named for what they mean. `pass/fail`
  belongs only to station result; `ok/skipped/<stable_error_code>` belongs only
  to operation outcome; `RUNNING/MERGED/...` belongs only to run state/outcome.
- Include the contract-version fields ADR-090 names:
  `measurement_version`, `emitter`, observation time, work-item identity, run
  identity, station identity, station-attempt identity, boundary, and
  capability tier. Keep optionality aligned with what an emitter can
  authoritatively attest; absence is preferable to synthesis.
- Use new schema versions for breaking field or vocabulary changes. Never edit a
  published version in place, including the station-id set or alias semantics.

## Security And Validation Layers

- Contract publication itself is static data, but every later write path that
  uses it must still pass the existing validation chain:
  `@Valid` request DTOs, immutable Command DTOs, `@Validated` services,
  project-scoped repositories, and `GroundControlException` through
  `GlobalExceptionHandler` to `ErrorResponse`.
- Keep the existing reserved-marker and closed-field-set rules in
  `WorkflowTelemetryService` authoritative for workflow telemetry writes. The
  measurement contract should inform allowed values, not replace those
  protections with ad hoc parsing.
- Keep sensitive-field exclusions unchanged across emitters: no prompts,
  completions, tokens, headers, issue bodies, review prose, raw payloads,
  stack traces, filesystem paths, or provider keys in measurement records,
  logs, metrics labels, or error envelopes.
- If retention, allowlists, or contract-selection knobs are needed later, bind
  them through validated `@ConfigurationProperties` objects. Do not introduce
  environment-string parsing or skill-prose configuration for this surface.

## Extensibility Seam

- The extensibility seam is the station catalogue and vocabulary version, not a
  new abstraction layer. Future stations, aliases, emitters, or consumers should
  extend the published data contract and version it under ADR-082 rather than
  requiring code edits across every emitter to rediscover the mapping.
- Leave room for multiple alias kinds and future emitters, but do not invent a
  generic ontology framework or runtime plugin system for measurement. The next
  reasonable change is "add a station or alias," not "replace the measurement
  model architecture."

## Gotchas And Anti-Patterns

- Do not publish a generic `ProcessMeasurement` catch-all entity, event bus, or
  dashboard schema.
- Do not let one free-form `phase`/`station` string remain the de facto source
  of truth after the catalogue is published.
- Do not treat `ready_for_review`, `traceability_reconciled`, or a SKILL step
  number as station identity merely because they are already emitted somewhere.
- Do not collapse operation outcome, station result, and run state into one
  enum, one field, one frontend badge vocabulary, or one reporting dimension.
- Do not create a second validation/error taxonomy, a measurement-specific error
  envelope, or a contract parser path that bypasses the existing backend and MCP
  validation layers.
- Do not duplicate the station catalogue independently in backend enums, MCP
  Zod enums, JSON Schemas, docs, and UI labels without one published contract
  artifact serving as the authority.

## Non-Goals

- No dashboard, roll-up job, retention implementation, REST endpoint, MCP tool,
  or backend persistence change in this issue.
- No attempt to backfill legacy station result from historical operation
  outcomes, event types, or merged PR state when the source never recorded it.
- No change to ADR-029 issue-thread authority, ADR-061 run ownership, ADR-036
  local JSONL opt-in behavior, or ADR-059's one-event-per-tool-call invariant.
