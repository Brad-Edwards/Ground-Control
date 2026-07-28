# Gate Outcome And Finding Measurement Preflight

Issue: #1355
Requirement: none

This note records the architecture disposition for emitting structured gate
outcomes and review findings. It is not an implementation plan.

## Boundary Decisions

- Extend ADR-061's workflow reporting projection. A station attempt remains a
  `WorkflowPhaseEvent` owned by `WorkflowTelemetryService`; finding rows are
  subordinate process observations linked to the terminal event for that
  attempt. Do not create a generic measurement aggregate, event bus, or second
  workflow state machine.
- Keep ADR-090's three axes separate. MCP/transport success is
  `operationOutcome`; the inspected gate's verdict is `stationResult`; run
  lifecycle remains `WorkflowRunState`/`WorkflowRunOutcome`. A backend outage,
  parser error, or timeout is `operationOutcome=error` plus
  `stationResult=not_evaluable`, never a failed or passed quality gate.
- Treat one terminal attempt event and its finding batch as one idempotent,
  transactional write. Finding counts and category/severity rollups are derived
  from the accepted rows, not trusted as an independent caller-supplied total.
  A retry must return the same attempt rather than append duplicate findings,
  and a partial finding batch must never appear as a complete gate outcome.
- Extend the published ADR-090 contract under ADR-082. The v1 station catalogue
  is immutable; adding `spotbugs`, `policy`, and `vale`, or changing
  `completion_gate` semantics, requires a successor catalogue/version and a
  `contracts/CHANGES.md` entry. Do not edit
  `gc-station-catalogue-v1.json` in place.
- Keep the product `Finding` aggregate out of this path. It models retained GRC
  findings with its own lifecycle and links; a review/scanner observation is
  workflow measurement, not a compliance finding and not evidence.
- ADR-029 issue comments remain the durable narrative and authorization record.
  The structured projection stores bounded facts and GitHub record identifiers,
  not comment bodies, reviewer prose, remediation text, or a replacement audit
  trail.

## Attempt, Cycle, And Result Semantics

- Emit an outcome for every actual station attempt, including pass, fail,
  skipped, cancelled, and not-evaluable attempts. A station attempt is created
  at the executor boundary, not inferred later from a final report or a merged
  pull request.
- `durationMs` uses a monotonic clock around the actual attempt. `observedAt`
  uses wall-clock time when the verdict occurred. Poll duration, queue delay,
  and time-to-retry are not repair time.
- Count review cycles as distinct evaluable attempts per `(run, station)`.
  Codex and test-quality are different stations and must not be summed into one
  synthetic cycle. Reviewer slices, async job polls, transport retries, and
  issue-comment posts are not review cycles.
- Do not use `MAX(cycle_index)` as an attempt count. Existing emitters mix
  zero-based attempt ordinals with one-based review-cycle labels. Iterations to
  green is derived by ordering distinct evaluable attempt identities and taking
  the first pass; unresolved runs remain unresolved.
- `completion_gate` is currently a composite station. A SpotBugs, policy, or
  Vale fact may be emitted only where that sub-gate actually executes and can
  provide its own duration and verdict. Do not assign the whole `make check` or
  `make policy` duration to a child station, parse a combined console transcript
  as several attempts, or execute a canonical gate twice merely to measure it.

## Finding Shape And Identity

- A finding record carries the parent attempt identity, a detector/reviewer id,
  source-native category or rule id, optional bounded category shape,
  source-native severity, classification when the source has one, and a
  disposition. It does not carry title/body/remediation prose, raw tool output,
  source code, repository paths, issue text, or stack traces.
- Keep detector and reviewer distinct. `core`, `security`, and `test-quality`
  are reviewers; SonarCloud, SpotBugs, Vale, policy, and CI are detectors. A
  non-review gate must not be assigned a fabricated reviewer.
- Preserve source severity exactly and aggregate it within a station. Current
  Codex core/security findings do not carry severity, and proposed ADR-031 is
  not an implemented source contract; record those as `unobserved` rather than
  guessing. Cross-station severity normalization requires a versioned,
  station-specific mapping with explicit unmapped coverage. It must not be
  hidden in query code or inferred from titles.
- Preserve stable source categories: Sonar rule/type, SpotBugs bug pattern,
  Vale check, policy `Violation.code`, CI job/step conclusion, and review
  `category.shape` where present. A one-off review finding has no recurring
  category shape; absence is preferable to a synthetic `"uncategorized"`
  category. Category shape is high-cardinality event data and must never become
  a Prometheus/Micrometer label.
- Finding identity is scoped to its attempt. Use a source-provided stable key
  when one exists; otherwise derive an opaque deterministic key from bounded
  structural fields before redaction. Never key on prose, array position,
  timestamp, or a heuristic cross-cycle match. Cross-cycle recurrence is a
  category aggregate unless a detector supplies a durable identity.

## Disposition Semantics

- Detection and disposition are different moments. A newly detected finding is
  `open`; terminal dispositions are exactly ADR-029's outcomes expressed as
  measurement facts: `fixed`, `wontfix`, or `not-applicable`. Transition is
  monotonic from open to one terminal value and idempotent.
- The current review-cycle wrapper posts `decision: fix` before the agent has
  repaired the tree. That value is intent, not proof of `fixed`, and must not be
  projected as a terminal disposition. `fixed` may be recorded only from a
  later tool-layer boundary that attests repair/verification.
- `wontfix` inherits ADR-029's explicit user-authorization requirement and
  source comment reference. `not-applicable` inherits the canonical decision
  record and rationale requirement. Measurement code must not create a second
  authorization or deferral vocabulary.
- A later green station attempt does not automatically prove every earlier
  finding fixed when detector version, configuration, or inspected scope
  changed. Automatic closure is safe only for a stable detector identity under
  the same declared scope/configuration; otherwise the disposition remains
  open until an authoritative tool-layer decision arrives.
- Completed-run aggregates must report open-disposition coverage. They must not
  silently drop open findings or count them as fixed, and a missing disposition
  is not `not-applicable`.

## Canonical Incumbents To Reuse

- `WorkflowRunController` -> immutable Command DTO ->
  `WorkflowTelemetryService` -> project-scoped
  `WorkflowPhaseEventRepository` is the existing write and transaction path.
  Extend it rather than adding a parallel controller/service/repository family.
- `workflow-run-lifecycle.js` supplies fail-open, FIFO, timestamped,
  run-correlated emission and `sourceId` idempotency. Extend its bounded station
  seam; do not reuse ADR-059's one-event-per-MCP-call record as gate truth.
- `gc-implement-mechanical.js` owns completion, policy, CI, and Sonar execution
  boundaries. `_runReviewCycleShared` and the existing Codex/test-quality
  parsers own structured review findings. `runWatchSonarAnalysis` already owns
  Sonar's structured issue export and safe summary.
- Use the tools' existing machine-readable sources instead of scraping prose:
  SpotBugs report data, Vale JSON, `Violation` objects from
  `tools/policy/checks.py`, GitHub Actions job/step conclusions, Sonar API
  issue/hotspot records, and the validated review envelopes. If a canonical
  command lacks a structured adapter, add it at that command's boundary; do not
  maintain a second implementation of the gate.
- Reuse MCP Zod schemas, explicit field allowlists, `buildUrl`,
  `addAuthorizationHeader`, `RequestError`, and `parseErrorBody`. Reuse
  Jakarta Bean Validation, immutable commands, service semantic validation,
  existing `GroundControlException` subclasses, `GlobalExceptionHandler`, and
  `ErrorResponse` on the backend.
- Reuse `ProjectService` and project-scoped repository queries for every
  finding write/read. Cross-project aggregation remains explicitly
  `ROLE_ADMIN` through `ApiPathMatrix`.

## Security, Validation, And Observability Layers

- The MCP boundary accepts only closed enums, bounded counts/identifiers, and
  an explicitly allowlisted finding shape. Existing review path containment,
  reserved-marker rejection, sensitive-content checks, and result-envelope
  parsers remain authoritative before emission.
- The REST path stays under both shared `/api/v1/**` security chains, the IP
  allowlist, project resolution, and `ActorFilter`/`ActorHolder`. A run or event
  UUID is not an authorization capability.
- `SONAR_TOKEN` remains header-only in `runWatchSonarAnalysis`; GitHub
  operations remain the existing MCP-owned `gh api` argv calls. No token,
  finding body, report payload, or secret-bearing environment value may enter
  process argv, measurement rows, logs, metrics, MCP responses, or
  `ErrorResponse.detail`.
- Backend validation enforces station/catalogue membership, event/result
  compatibility, batch bounds, source-id uniqueness, monotonic disposition,
  project ownership, and count consistency. Database constraints backstop
  idempotency and referential integrity; validation is not duplicated only in
  Zod or only in a JSON Schema.
- Emission remains fail-open with respect to gate control flow. Log only safe
  project/run/station ids, counts, and a stable failure class through SLF4J or
  the existing bounded MCP diagnostic. Never log the finding batch or backend
  response body.
- No new environment variable is needed for the basic path. If batch,
  retention, or normalization bounds become configurable, bind them once with
  validated `@ConfigurationProperties`; do not parse environment strings in
  MCP helpers or skills.

## Reliability And Test Guardrails

- Exactly once logical facts are enforced by `(run_id, source_id)` for attempts
  and an attempt-scoped finding key. At-least-once delivery, live emission, and
  reconciliation must converge; an HTTP retry or process restart must not
  increase attempt or finding counts.
- Pass attempts persist an explicit zero-finding batch. Skipped/cancelled/
  not-evaluable attempts preserve coverage but stay out of FPY and
  iterations-to-green denominators.
- Repository/database tests pin project scoping, atomic batch persistence,
  duplicate delivery, terminal-disposition conflicts, and database-side
  aggregates. Service unit tests pin formulas and missing/unobserved coverage.
  `@WebMvcTest` slices pin Bean Validation and the shared error envelope because
  Testcontainers tests do not contribute to Sonar coverage.
- MCP tests pin one execution per gate, fail-open behavior, timeout/cancel
  classification, safe logs, source-adapter mappings, and that reviewer slices
  or polls do not increment cycles. Contract/policy tests pin catalogue
  versioning, closed vocabularies, and emitter-to-catalogue drift.

## Extensibility Seam

The seam is a registered station plus a small source adapter that returns the
same bounded attempt/finding batch. The next detector should add catalogue data
and one adapter, not edit aggregation formulas, invent another finding schema,
or teach the workflow service a detector-specific parser. Optional
cross-station severity normalization belongs in separately versioned mapping
data so one new detector does not require rewriting existing records.

## Gotchas And Anti-Patterns

- Do not treat tool success, event type `COMPLETED`, CI transport success, an
  empty parser result, or a merged PR as `stationResult=pass`.
- Do not parse GitHub issue prose, Gradle console text, final reports, or log
  summaries when the owning gate exposes structured data.
- Do not import full Sonar/SpotBugs/Vale/policy/CI payloads or review prose into
  PostgreSQL “for later”; retain only the closed measurement projection.
- Do not mutate published v1 contracts, duplicate station enums across layers,
  infer severity from reviewer identity/title, map `fix` intent to `fixed`, or
  infer finding identity across cycles from similar prose.
- Do not turn measurement into workflow authority, a retry counter, a new
  quality gate, compliance evidence, or a graph-projected product `Finding`.

## Non-Goals

- No dashboard, alerting rule, OTel metric export, retention/roll-up job, or
  historical prose backfill in issue #1355.
- No new reviewer rubric, severity-weighted stopping model, workflow gate,
  human approval, or change to the configured gate commands.
- No escape-rate attribution, active repair-time measurement, product Finding
  creation, or raw-report browser.
- No replacement for ADR-029 issue records, ADR-036 local economics telemetry,
  ADR-059 MCP tool telemetry, or ADR-061 run reporting.
