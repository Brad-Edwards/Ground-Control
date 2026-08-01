# Live Activity View Preflight

Issue: #1437
Requirement: none

This note fixes the architecture boundaries for the project-scoped operations
view. It is design guidance, not an implementation plan.

## Boundary Decisions

- Add a separate **Live Activity** project workspace. Do not turn the existing
  Workflow Runs economics/history page or the future rolling scorecard into an
  operations screen. The recently finished band is context only; Workflow Runs
  remains the historical owner and must actually keep terminal runs reachable.
- Build one bounded activity read projection over the existing ADR-061 owners:
  `WorkflowRun`, `WorkflowPhaseEvent`, and subordinate
  `WorkflowGateFinding` rows. Reuse `WorkflowTelemetryService` and
  project-scoped repositories. Do not add a live-run table, cache, event store,
  workflow state machine, or product `Finding`.
- Fetch open runs and their gate summaries in batches, not by calling
  `GET /{runId}/events` once per row. Include all open states defined by
  `WorkflowRunState` (`RUNNING`, `READY_FOR_REVIEW`, `ESCALATED`), a bounded
  last-N terminal band, and explicit truncation/total metadata wherever a
  safety cap applies. A cap must never silently hide open runs.
- Derive the current phase only from the latest
  `ADR061_WORKFLOW_TELEMETRY` lifecycle event, ordered deterministically by
  observation time and stable identity. ADR-036 step observations are economic
  and routing facts, not lifecycle transitions. With no lifecycle event, show
  the phase as unobserved; do not invent one from a SKILL step, tool name,
  `next_action`, branch, or run state.
- An executing gate is a catalogue-resolved station attempt with `STARTED` and
  no terminal event for the same station/attempt. A terminal gate strip cell
  uses its explicit `stationResult`, recorded `durationMs`, cycle index, and a
  database count of subordinate findings. Preserve `findingsDropped`
  separately so a truncated batch never appears complete. Never derive pass or
  fail from `PhaseEventType`, tool success, run outcome, or an empty/missing
  finding batch.
- Model and tier are per-step ADR-036 routing observations. Read the latest
  applicable `ADR036_STEP_JSONL` event and show its stage with the model/tier.
  Do not read `.ground-control.yaml` outside the ADR-027 context tool, and do
  not present `WorkflowRun.model` (an economics/import field) as the current
  routed model. Missing routing observation stays unobserved.

## Time And Stalled Semantics

- “Stalled” is a derived attention condition, never a persisted
  `WorkflowRunState`, phase event, workflow verdict, lease, heartbeat, or
  control signal. `RUNNING` still means only that no terminal observation was
  recorded. The UI wording must say that a threshold was exceeded or that a
  run is possibly stalled; it must not claim the agent process is alive or
  dead.
- The current-phase anchor is the latest lifecycle transition time. With no
  phase observation, use the run start only to report “no phase observed for
  …”, not to name a phase. `READY_FOR_REVIEW` and `ESCALATED` remain visibly
  distinct open states; exceeding the threshold while paused means
  “waiting longer than threshold,” not “gate execution wedged.”
- Live elapsed time is `asOf - startedAt`; time in phase is
  `asOf - currentPhaseSince`. Do not reuse `wallClockMinutes`, which is an
  imported economics value, or recompute completed gate duration from wall
  timestamps when the monotonic `durationMs` fact exists.
- The REST snapshot supplies a server `asOf` and the effective stall threshold
  for each row. The browser advances clocks from that anchor with one timer and
  can cross the threshold without waiting for another backend event. Clamp or
  render unavailable for future/malformed timestamps; never display negative
  duration.
- Bind the positive global threshold under a narrow validated
  `groundcontrol.workflow-telemetry.activity.*`
  `@ConfigurationProperties` object. Carry the **effective per-row threshold**
  on the response so a later station-specific override can be added inside the
  same configuration/service seam without changing the wire contract.

## Live Delivery And Frontend Contract

- Keep #1436's stream unchanged: named `workflow-run` and `phase-event` frames
  remain the existing REST response shapes. Do not add an activity-only SSE
  envelope, finding-count event, replay cursor, browser event store, or second
  station vocabulary.
- Initial mount, reconnect, and degraded polling fetch the activity REST
  snapshot. Either SSE event invalidates the shared activity React Query key;
  the post-commit phase notification is emitted after the attempt and finding
  batch commit, so the refetch sees one atomic verdict. This also moves a
  terminal run from the open band into recent context without a reload.
- Reuse `workflowRunKeys`, `useWorkflowRunStream`, its same-origin
  `EventSource`, and its `Live`/`Reconnecting`/`Polling` honesty. Extend the
  existing cache reconciliation; do not create competing React state.
- The backend/OpenAPI document remains the semantic source and generated
  TypeScript remains the compile-time consumer (ADR-082). SSE ingress keeps a
  narrow runtime project/shape check. Do not hand-mirror DTOs or enums in
  `frontend/src/types/api.ts`.
- Use the existing shell, semantic status badges/tokens, loading/error/empty
  states, and responsive table/card patterns. The gate strip must not rely on
  color alone and must expose state and timing text to assistive technology.

## Cross-Cutting Layers And Canonical Incumbents

- **Boundary:** `WorkflowRunController` -> domain service -> project-scoped
  repositories, respecting `api/ -> domain/ <- infrastructure/`. Controllers
  do not query repositories or interpret station history.
- **Persistence:** existing audited ADR-061 aggregates, source-id
  idempotency, station catalogue, emitter discriminator, and finding foreign
  keys. No new persistence concept is needed. Any new index must support the
  measured activity query shape and arrive through a forward Flyway migration;
  do not add speculative indexes or filter the global population in memory.
- **Validation:** Jakarta validation for bounded query parameters, service
  validation for project/time/state semantics, `StationCatalog` for station
  identity, generated OpenAPI types for the frontend, and a narrow runtime SSE
  guard. Keep limits finite and make null/unobserved fields explicit.
- **Errors:** existing `GroundControlException` subclasses,
  `GlobalExceptionHandler`, and `ErrorResponse`. Do not add activity-specific
  envelopes or reflect repository, payload, credential, or stack-trace detail.
- **Logging/metrics:** SLF4J structured logs with bounded project/run/station
  identifiers and stable reason codes. Do not log event/finding payloads,
  cookies, authorization headers, issue bodies, branches in metric labels, or
  one log line per browser clock tick. Metrics use only bounded state/reason
  labels.
- **Testing:** `@WebMvcTest` for the controller and response/error contract;
  service/repository tests for project isolation, deterministic current-phase
  selection, open/terminal partitioning, attempt pairing, finding counts, and
  bounded reads; security-enabled tests for anonymous denial and session/bearer
  access; frontend fake-timer tests for threshold crossing, reconnect
  resynchronization, project changes, polling degradation, and accessible
  state text. Keep controller coverage outside Testcontainers because the
  Sonar job does not run integration tests.

## Security And Deployment Guardrails

- The snapshot and existing stream stay under the shared authenticated
  `/api/v1/**` bearer/session path matrix, `IpAllowlistFilter`, and
  `ProjectService` resolution. A run/event UUID is not an authorization
  capability. This is project scoping, not tenancy.
- Browser reads use the existing same-origin session cookie. No new mutation
  means no new CSRF surface; do not put a bearer token in an EventSource URL,
  local storage, query string, or process argument.
- The response remains the closed redacted telemetry projection: safe
  correlation ids, branch, machine station ids, bounded model/tier facts,
  counts, outcomes, and durations. It never includes prompts, completions,
  issue/review prose, raw scanner output, file paths, credentials, or secret
  environment values.
- Local-only is a deployment posture, not feature code. Preserve the existing
  `GC_BIND_IP`, source-IP allowlist, bearer/session authentication, and
  red-dragon firewall posture; add no localhost bypass, local-only role,
  activity feature flag, or alternate public port.
- The non-secret stall-duration binding must stay synchronized across
  `application.yml`, production Compose passthrough,
  `deploy/docker/env.schema`, `.env.example`, and deployment documentation.
  Spring validates the typed duration at startup; deployment validation names
  variables only; the value never belongs in process argv. Regenerate the
  deployment manifest when canonical deploy artifacts change.

## Gotchas And Non-Goals

- Historical terminal rows may lack a trustworthy `endedAt`; do not hide them
  or manufacture one. Use an explicit fallback ordering only if the response
  also preserves that finish time is unobserved.
- Event delivery can duplicate or be lost; entity/source identity plus REST
  resynchronization remains the recovery path. Stream connection health is not
  workflow health.
- Do not add start/cancel/retry/signal actions, stale-run reaping, a scheduler
  that mutates runs, alert delivery, cross-project activity, rolling yield,
  iterations-to-green trends, or retention work.
- Do not duplicate the station catalogue in React, parse display labels as
  identities, infer a single run-wide model/tier, perform N+1 event/finding
  reads, or compute current phase from a bounded tail that can omit its matching
  start.
