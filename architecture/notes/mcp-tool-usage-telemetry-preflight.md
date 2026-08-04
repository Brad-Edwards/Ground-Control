# MCP Tool Usage Telemetry Preflight

Issue #1104 adds server-side usage telemetry for the Ground Control MCP
adapter: one event per MCP tool call, backend aggregation over a time window,
and fail-open capture. This note records the architecture guardrails. It is
not an implementation plan.

## Boundary Decision

- Capture belongs at the MCP tool-handler boundary, not inside the shared
  HTTP `request()` helper and not inside each backend endpoint. The
  acceptance criterion is exactly one event per MCP tool call; helper-level
  instrumentation would over-count tools that make multiple backend calls and
  could recursively count the telemetry write itself.
- The Spring backend is the persistence and aggregation authority. MCP records
  a closed telemetry DTO and sends it through the existing REST/auth/error
  path; it must not write local JSONL for this issue and must not analyze raw
  rows client-side.
- ADR-036 step telemetry remains workflow-run economics only. MCP usage
  telemetry is operational product telemetry: not workflow state, not a cycle
  counter, not a decision record, and not GRC evidence.
- Keep a distinct domain slice for MCP usage telemetry. Do not hide this under
  requirements analysis, audit, evidence, or derivation domains just because
  the read side is an aggregate.
- The event table should be append-only operational data. Do not add Envers
  audit tables unless a later product requirement needs auditing telemetry-row
  mutation; the current design should avoid row mutation entirely.

## Event Shape Guardrails

- Store only the allowlisted fields from the issue contract: `tool`, `action`,
  `outcome`, `duration_ms`, `project`, and `ts`. Backend-created `id`,
  `created_at`, or `received_at` columns are fine, but prompts, request bodies,
  response bodies, paths, params, headers, tokens, stack traces, and secret-
  shaped values are not telemetry fields.
- Keep `tool` and `action` separate. `tool` is the registered MCP tool name;
  `action` is the action discriminator when a consolidated tool has one. For
  single-action tools, use a stable null or fixed value consistently rather
  than folding it into `tool`.
- `outcome` is `ok` or a stable error code. Do not persist exception messages
  as outcomes; messages can contain reflected user input or operational detail.
- `project` is nullable and should be a bounded project identifier signal, not
  inferred from arbitrary request payloads. Workflow tools and project-list
  calls may legitimately have no project.
- `duration_ms` is measured around the original tool handler and is
  non-negative. The telemetry write latency is not part of the original tool
  duration.

## Cross-Cutting Concerns to Reuse

- **MCP validation:** use Zod or a similarly closed internal shape for the
  telemetry write body. This is not a public caller argument surface, so do not
  expose telemetry fields to users or allow unknown keys through.
- **MCP transport/auth:** reuse `buildUrl`, `addAuthorizationHeader`,
  `RequestError`, and `parseErrorBody` from `mcp/ground-control/lib.js`.
  Tokens stay in environment or `.env`; they must not appear in tool args,
  logs, process argv, or returned errors.
- **Backend validation:** use API request records with Jakarta Bean Validation
  for size, nullability, timestamp, and non-negative duration checks. Domain
  commands stay immutable and validated at the service boundary.
- **Backend errors:** throw existing `GroundControlException` subclasses for
  backend validation or lookup failures and let `GlobalExceptionHandler` emit
  the `ErrorResponse` envelope. Do not create a telemetry-specific HTTP error
  envelope.
- **Backend project handling:** if the read endpoint filters by project, route
  through `ProjectService` so the project contract matches the rest of the
  repo. Do not make project-less queries silently pick a different project.
- **Logging:** use SLF4J with structured fields for telemetry write failures,
  but log only the tool name, outcome code, and high-level failure class. Do
  not log payloads or bearer material.
- **Security:** the endpoint remains under `/api/v1/**` and should pass the
  existing `IpAllowlistFilter`, bearer/session auth chain, `ApiPathMatrix`,
  and `ActorFilter`. If a later change makes the read aggregate admin-only,
  update the shared path matrix and MCP token routing together.
- **Persistence:** use a Spring Data repository for inserts and aggregation
  queries. Index for the aggregation access pattern: time window first, with
  project/tool grouping support.
- **Tests:** new controllers need `@WebMvcTest` coverage under
  `backend/src/test/java/com/keplerops/groundcontrol/unit/api/`; repository or
  service tests should cover percentile math. MCP unit tests must prove exactly
  one event on success, exactly one event on handler error, and fail-open when
  the telemetry write fails.

## Security Layers In Scope

- **MCP handler wrapper:** measure and classify the original handler result
  before any telemetry write. The wrapper must record `ok` or a stable
  `RequestError.code` / internal error code, never an exception message,
  stack, tool payload, or response body.
- **MCP outbound REST adapter:** telemetry writes use the existing
  `request()`/`buildUrl()` path and bearer-token selection. No shelling out to
  `curl`, no caller-supplied headers, and no token in process argv.
- **Backend request binding:** the capture endpoint accepts a request DTO with
  Jakarta validation for field length, timestamp, duration, and outcome shape.
  Unknown or oversized data should fail at the API boundary before reaching
  persistence.
- **API security matrix:** both capture and aggregation live under
  `/api/v1/**`, so they pass `IpAllowlistFilter`, bearer/session
  authentication, `ApiPathMatrix`, and `ActorFilter`. If the read endpoint is
  made admin-only later, the shared matrix and MCP admin-token routing must
  change together.
- **Project scope:** any project filter or project column uses the canonical
  project identifier contract and `ProjectService` lookups. Do not infer a
  project from arbitrary nested tool payloads.
- **Error envelope:** backend failures serialize through
  `GlobalExceptionHandler` and `ErrorResponse`; MCP converts REST failures via
  `RequestError`/`parseErrorBody`. Telemetry failure must never replace the
  original tool result.
- **Logging and OS exposure:** warning logs for failed telemetry capture may
  include tool, action, and failure class only. Do not log args, prompts,
  params, bearer tokens, environment dumps, response bodies, or stack traces
  from user-controlled failures.

## Aggregation Guardrails

- The read endpoint returns per-tool counts, error rates, and named latency
  percentiles over the requested time window. Choose stable percentile names
  such as p50/p95/p99 and keep them in one backend response shape.
- Validate or bound the time window so an unintentional all-history scan is a
  deliberate API decision. If defaults are added, make them explicit in the
  controller/service rather than burying them in SQL.
- Compute aggregates server-side. Do not revive the retired local JSONL
  economics tooling (the former `tools/summarize_implement_telemetry.py`,
  removed in #1507); it had a different schema and scope.
- Keep the obvious extension seam in one place: the percentile set and any
  default/max window policy belong in backend service/config, not duplicated in
  MCP or README prose.

## Whole-Repo Surfaces In Scope

- `mcp/ground-control/index.js`: the single MCP registration surface where the
  handler wrapper belongs. This is where "one event per MCP tool call" is
  enforceable.
- `mcp/ground-control/lib.js`: the existing REST adapter, bearer forwarding,
  error parsing, and any internal helper that posts telemetry to the backend.
- `mcp/ground-control/gc-query.js`,
  `mcp/ground-control/README.md`, and `mcp/ground-control/gc-query.test.js`:
  add the aggregation read prefix to the canonical allowlist and keep README /
  ADR drift tests green.
- `backend/src/main/java/com/keplerops/groundcontrol/api/**`: add only a thin
  controller and API DTOs; controllers do not import repositories or domain
  entities.
- `backend/src/main/java/com/keplerops/groundcontrol/domain/**`: place the
  service, event aggregate/read model, command, and repository in a dedicated
  MCP telemetry domain slice.
- `backend/src/main/resources/db/migration/`: add one Flyway migration for the
  event table and indexes aligned to time-window aggregation.
- `backend/src/main/java/com/keplerops/groundcontrol/shared/security/`: reuse
  the existing `/api/v1/**` authentication path; touch the shared path matrix
  only if the read/write privilege level intentionally differs from normal
  authenticated API reads.
- `backend/src/test/java/com/keplerops/groundcontrol/unit/api/` and
  `mcp/ground-control/*.test.js`: controller-slice and MCP handler-wrapper
  tests are mandatory because they prove the acceptance criteria in the CI
  lanes that actually run for coverage.
- The former `tools/summarize_implement_telemetry.py` (removed in #1507) and
  `.gc/telemetry/` are explicitly out of scope for this issue except as "do not
  reuse" examples.

## Implementation Boundaries

- Add a capture endpoint, aggregation endpoint, event table, service, repository
  query, and MCP handler-boundary instrumentation only. Keep all analysis of
  "catalog weight" or drift detection for follow-on work that consumes the
  aggregate.
- Keep telemetry best-effort and fail-open. If the backend is unreachable,
  auth rejects the telemetry write, or validation fails because the wrapper
  would have emitted an unsafe event, return the original tool result and log a
  bounded warning.
- Keep the event schema small and versionless unless a real second producer or
  breaking shape appears. Do not introduce a generic observability framework or
  OpenTelemetry bridge for v1.
- Prefer backend service constants or configuration properties for default/max
  aggregation window and percentile set; MCP and docs should refer to the
  endpoint contract, not duplicate policy.

## Gotchas and Anti-Patterns

- Do not instrument `request()` in `lib.js`; that counts backend calls, not MCP
  tool calls, and it risks telemetry-write recursion.
- Do not let telemetry capture change the original tool result. Telemetry
  write failures are warning logs and swallowed errors after the original
  outcome has been determined.
- Do not post telemetry through `gh`, `git`, `curl`, or agent shell commands.
  The MCP server owns privileged side effects and should use its existing
  HTTP adapter boundary.
- Do not duplicate enum registries for outcomes unless the values are mirrored
  across backend/MCP and covered by the existing enum drift policy. A small
  string code with validation is enough for v1.
- Do not model these rows as audit history, evidence artifacts, derivation
  facts, or issue-thread records. Those domains answer different questions and
  carry stronger semantics than usage counters.
- Do not update only `GC_QUERY_PATH_ALLOWLIST`. Adding the aggregation read
  path also requires the MCP README and ADR-035 allowlist drift surfaces to
  stay in sync.

## Non-Goals

- No prompts, payloads, secrets, response bodies, or stack traces in telemetry.
- No DAST/runtime tracing, OpenTelemetry collector integration, or agent-host
  local telemetry replacement.
- No change to ADR-036 step telemetry JSONL or its summarizer.
- No new MCP public tool solely for telemetry capture; capture is internal to
  the adapter.
- No change to the backend API authorization model unless explicitly decided
  with the shared path matrix.
