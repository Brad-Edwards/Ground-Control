# Temporal LLM Provider Boundary Preflight

Issue #1280 is GC-O009 phase 5: add provider-neutral LLM-backed activities,
select provider/model per project and ADR-036 stage, and prove that model inputs,
outputs, and credentials do not leak into durable or public surfaces. This note
is architecture guidance only. It does not implement a provider, activity,
configuration parser, bridge, REST/MCP field, persistence model, or workflow
change.

## Boundary Decisions

### Routing is trusted control-plane data

- `.ground-control.yaml` remains the only project workflow configuration
  schema. Only `gc_get_repo_ground_control_context` and
  `gc_resolve_workflow_route` read and validate it (ADR-027/ADR-036). The Java
  backend must not add another YAML parser, routing table, tier vocabulary, or
  per-project provider/model table.
- Resolve each LLM route from a trusted base/default-branch configuration
  snapshot before starting the workflow, then bind the normalized, safe route
  metadata to the execution. Do not re-read a mutable feature-worktree config
  on an activity retry: a change under implementation must not be able to
  redirect its own source/context to another provider or increase its model
  spend. Reuse the trusted-ref posture already used by
  `readVocabularyForReview`, and retain a safe configuration digest/version for
  provenance.
- The public workflow-start REST/MCP request must not acquire caller-supplied
  `provider`, `model`, endpoint, credential, prompt, or completion fields. The
  existing authenticated start boundary resolves the project with
  `ProjectService`; a trusted configuration port supplies the route behind that
  service boundary. Until the normalized-config handoff is available, an
  LLM-backed start fails closed rather than selecting a process-global default.
- Route resolution happens synchronously before `WorkflowControlPort.start` so
  disabled routing, an unknown stage/provider, an unavailable adapter, missing
  credentials, an invalid model, or a non-executable fallback returns an
  existing `GroundControlException` through `GlobalExceptionHandler` and
  `ErrorResponse`. The activity repeats the safe availability/model checks as
  defense in depth, but a normal start must not create a workflow already known
  to be unrunnable.
- Preserve the ADR-036 stage/tier vocabulary exactly. The first LLM-backed plan
  activity uses stage `planning`; activity method names are not a second stage
  catalog. `agent: parent|subagent|cli` and `fallback: parent` are legacy driver
  placement semantics, not executable Temporal-worker fallbacks. A required
  server-side LLM activity accepts only a resolved provider/model or an explicit
  fail-closed outcome; it never interprets `parent` as a hidden model choice.
- Keep provider and model concepts distinct. The infrastructure adapter's
  canonical provider id is `anthropic`; `claude-*` is a model family/id, not a
  provider id. The existing routing parser currently accepts only the legacy
  provider label `claude`. Compatibility, if retained, belongs in that one
  parser as an explicit `claude -> anthropic` normalization with canonical
  output. Java activity code must not repeat or guess that alias.

The safe route snapshot may contain only bounded scalars such as contract
version, project, stage, tier, canonical provider id, canonical model id, and a
configuration digest. It contains no endpoint, credential reference/value,
prompt template/body, completion, repository content, or provider-native
options. The snapshot may live in Temporal history because it is the durable
execution decision; it need not become a Search Attribute or a PostgreSQL
workflow-state row.

### The domain port is narrow and Temporal-neutral

- Put the LLM invocation port and its provider-neutral in-memory request/result
  types in `domain/`; put Anthropic HTTP/configuration code in
  `infrastructure/`. Neither side imports Temporal types. The Temporal activity
  is an infrastructure adapter that resolves context, invokes the domain port,
  and returns only the existing bounded workflow result.
- Reuse the useful part of `EmbeddingProvider`: domain port plus infrastructure
  adapter. Do not copy its global/single-provider shape, `isAvailable()`
  no-op/fail-open behavior, model getter, `@Value` configuration, raw
  `IllegalStateException`, provider response logging, or secret-bearing
  properties record.
- The port accepts the model for this invocation plus activity-owned prompt
  content and bounded generation policy; it does not receive credentials,
  endpoints, Temporal context, project routing rules, provider-native request
  objects, or arbitrary option maps. The adapter owns credentials and maps the
  neutral request onto its provider protocol.
- Provider selection uses a small classpath registry keyed by stable canonical
  provider id, following `PackTypeHandlerRegistry`'s duplicate-registration and
  fail-closed lookup posture. It is not a `PluginRegistry` entry, dynamic code
  loader, provider marketplace, or generic `execute(Map)` framework (ADR-023 /
  ADR-028).
- Sensitive in-memory carriers must not be JPA entities, Temporal/REST DTOs, or
  records whose generated `toString()` prints prompts, completions, headers, or
  keys. If a carrier can be stringified, its representation is explicitly
  redacted. Provider response objects stay private to the adapter.

Operational adapter configuration is separate from project routing policy.
Typed `@ConfigurationProperties` may bind the worker's Anthropic API base URL,
credential, connect/read timeouts, and response-size limits. Those properties
say which adapter capability the deployment has; `.ground-control.yaml` says
which provider/model a project selects. The operational properties must not
grow a second set of per-project stage/model choices.

### Activity history contains references and facts, not model data

- The first LLM-backed use case is plan authoring. The workflow supplies the
  resolved project and route plus issue/requirement/workspace references. The
  activity resolves the referenced content and builds the prompt inside the
  activity process. Raw issue bodies, repository text, diffs, prompts, and
  completions are not Temporal inputs or results.
- `AuthorPlanInput` currently lacks the project/route needed for project-scoped
  resolution. Do not infer project ownership from the issue number, a local
  checkout, or by parsing the workflow id inside the activity. Correct the
  contract through the ADR-082 versioned schema surface before wiring an
  implementation. Adding a required field to published
  `content-activities.v1` is a breaking contract change; use an explicit new
  version/declaration and keep `WorkflowContractConformanceTest` plus the
  `workflow-payload-contract` policy bijection valid.
- `AuthorPlanResult(posted, commentId)` is already the right durable shape: the
  comment id is a reference, while plan prose is absent. Keep the completion
  in process and pass it to the canonical plan-publication boundary; return the
  marker/comment facts only. Large inputs and outputs cross durable boundaries
  by bounded ids, hashes, locators, or comment/artifact references.
- Reuse `gc_post_implementation_plan` semantics for publication: preflight and
  GRC-screening prerequisites, GRC deliverable coverage, reserved-marker
  rejection, `detectSensitiveBodyContent`, body bounds, the plan phase marker,
  and GitHub posting remain one canonical policy surface. A Java activity must
  not recreate weaker plan-marker/GRC/secret rules. The transition bridge may
  adapt that existing capability; until it does, publication is unavailable,
  not silently replaced by a direct `gh` call.
- Repository/issue content and model output are untrusted data. They cannot
  choose provider, model, endpoint, credentials, retry policy, tool names,
  command arguments, authorization, or workflow phase. Model output becomes
  effective only after the existing deterministic validators and gates accept
  it.

`ImplementContentActivities` is a provisional mixed seam: it contains
LLM-backed authoring/review methods and deterministic readiness/final-record
methods. Do not implement that conceptual mix as one class whose provider
dependency reaches every method. Preserve existing Temporal activity names and
replay compatibility where possible, while separating LLM invocation,
deterministic record publication, and orchestration into collaborators that can
be guarded independently. `ImplementActivities` and every deterministic
activity implementation remain structurally unable to depend on the LLM port,
provider registry, provider adapter, or provider HTTP types.

### Failure, retry, and idempotency semantics are explicit

- Route/configuration failures and provider 4xx failures caused by credentials,
  request shape, or model selection are controlled, non-retryable failures.
  Timeouts, connection failures, rate limits, and eligible provider 5xx
  responses may be retried by a bounded LLM-specific Temporal activity policy.
  Never surface a raw provider body/header or `RestClientResponseException`
  message/cause; those frequently echo request/response content.
- Do not reuse `ImplementActivityOptions.standard()` blindly. LLM calls have
  cost and longer latency. Their start-to-close timeout, maximum attempts, and
  retryable status catalog are explicit and bounded. Temporal retry attempts
  are infrastructure retries, not Codex/test-quality review cycles and must not
  increment review caps or issue-thread cycle markers.
- Temporal activities are at-least-once. Check the stable idempotency key and
  existing plan marker/comment before invoking the provider, and make
  publication observe-before-create. Do not claim exactly once inference or
  billing: a worker can crash after a successful provider call but before a
  durable reference is written. A future content cache may reduce that narrow
  duplicate-cost window, but it is not permission to put the completion in
  Temporal history or invent a general blob store now.
- Provider/model fallback is a routing decision, not an exception handler. An
  absent adapter, missing key, invalid model, or exhausted provider does not
  silently fall back to another credential/provider/model.

## Cross-Cutting Layers The Design Must Pass

| Layer | Required behavior and incumbent |
|---|---|
| Trusted repo configuration | `parseGroundControlYaml` / `normalizeRoutingConfig` / `resolveWorkflowRouteFromConfig` keep strict unknown-key, stage/tier/model validation and provider normalization. Resolve from the trusted base config and bind a digest; never parse YAML in Java or trust the feature branch to select its own egress. |
| REST/MCP shape and auth | Keep `StartWorkflowExecutionRequest` and `gc_workflow_execution` closed to model data and secrets. Requests pass bearer/session security, IP/CSRF rules, `ProjectService` scoping, and the current authenticated start policy. Any future route-management write needs an explicit `ApiPathMatrix` + `contracts/authz/path-matrix.yaml` decision. |
| Domain validation | `WorkflowExecutionService` resolves the project and safe route before `WorkflowControlPort.start`; immutable command records carry only normalized route metadata. Use existing `DomainValidationException` / `ServiceUnavailableException` categories, not a parallel exception hierarchy. |
| Error envelope | `GlobalExceptionHandler` + `ErrorResponse` remain the only HTTP failure shape. Messages expose stable product codes/field names only, never provider bodies, headers, URLs with credentials, model output, stack traces, Temporal internals, or secret values. |
| Deployment configuration | Use strict `@ConfigurationProperties` registered by `GroundControlApplication`. Wire only the worker in `docker-compose.yml` and `deploy/docker/docker-compose.prod.yml`; update `application.yml`, `deploy/docker/env.schema`, its validator/policy tests, and the deploy manifest when the operational env surface changes. A missing selected-provider credential fails closed. |
| OS/container exposure | The key is never a command argument, URL query value, file name, GitHub body, or child-process environment. If the incumbent environment binding is used, limit it to `temporal-worker` (not the web backend) and acknowledge that host/container administrators can inspect that process environment. Temporal Web/gRPC and the worker actuator remain non-public infrastructure surfaces. |
| Provider HTTP edge | Use Spring `RestClient` (the existing HTTP client convention) with a fixed/operator-configured HTTPS endpoint, authorization header, bounded connect/read timeouts, bounded request/output tokens and response bytes, strict required-field checks, and no body/header wire logging. Project/caller/model output cannot choose the endpoint. |
| Temporal history/visibility | Workflow/activity records continue to conform to `contracts/schemas/workflow/`. History/Memo/Search Attributes contain only the closed safe set; route metadata is safe history but prompts/completions/provider payloads/keys are not. Workflow code never calls the provider directly. |
| Persistence/audit | Add no LLM transcript or credential entity. `WorkflowRun` may continue to project safe provider/model/token-count economics but never drives execution. `OperatorSignalAudit`, Envers rows, graph projections, and any correlation record stay closed to prompts, completions, raw provider errors, and keys. Background activities use a system/worker identity; they do not reuse or spoof request-thread `ActorHolder` state. |
| Logging/metrics | Use SLF4J/Logback safe fields only: project, workflow/run/activity id, attempt, stage, tier, canonical provider/model, duration, token counts, outcome, and controlled error code. Never log request/response objects, prompt/completion fragments or hashes that enable recovery, authorization headers, keys, raw exceptions, or repository content. Temporal visibility supplies duration/retry facts; ADR-061 remains a reporting projection, not workflow state. |
| Durable issue record | Plan publication stays behind the ADR-029/ADR-036 MCP marker and sensitive-content boundary. The completion may be an internal argument to that adapter, but it is never returned in a REST/MCP response or copied into Temporal history. |
| Contract/policy tests | Reuse ADR-082 schema conformance, `contracts/CHANGES.md`, `ArchitectureTest`, parser tests, Temporal test environment, controller slices, MCP/OpenAPI drift tests, deploy-env consistency, and `make policy`. Do not create an LLM-only schema or validation stack. |

## Canonical Incumbents To Reuse

- `gc_get_repo_ground_control_context`, `normalizeRoutingConfig`,
  `DEFAULT_IMPLEMENT_ROUTING_STAGES`, `resolveWorkflowRouteFromConfig`, and
  their `mcp/ground-control/lib.test.js` coverage own route vocabulary and
  defaults.
- `WorkflowExecutionController` -> `WorkflowExecutionService` ->
  `WorkflowControlPort` -> `TemporalWorkflowControlAdapter` owns authenticated,
  project-scoped start and the standard error boundary.
- `ImplementWorkflowImpl`, `ImplementContentActivities`, the content activity
  records, `contracts/schemas/workflow/`, `contracts/CHANGES.md`,
  `WorkflowContractConformanceTest`, and the workflow payload policy check own
  Temporal call shape and contract versioning.
- `EmbeddingProvider` demonstrates the port/adapter direction only;
  `PackTypeHandlerRegistry` demonstrates exact classpath selection and duplicate
  rejection. `PluginRegistry` is deliberately not the provider execution
  mechanism.
- `gc_post_implementation_plan`, `detectSensitiveBodyContent`, phase-marker
  parsers, and GRC deliverable validation own durable plan publication.
- `@ConfigurationProperties`, `application.yml`, both compose files,
  `deploy/docker/env.schema`, `validate-env.sh`, deploy artifact consistency,
  and `MANIFEST.sha256` own runtime configuration exposure.
- `GlobalExceptionHandler`, `ErrorResponse`, existing
  `GroundControlException` subclasses, SLF4J/Logback, and the existing
  `WorkflowRun` redacted economics projection own errors and observability.

## Required Structural And Non-Leak Evidence

- ArchUnit must prove every deterministic activity type/implementation is free
  of the LLM port, registry, adapters, provider SDK/HTTP DTOs, and provider
  configuration. A companion rule should make direct provider calls outside
  the approved infrastructure adapter package fail.
- Parser/resolver tests cover canonical provider normalization, provider-aware
  model validation/defaults, trusted-base resolution, disabled routing,
  unknown stages/providers, and non-executable server fallbacks. A feature-branch
  config change must not change the route of the run evaluating that change.
- Provider adapter tests use sentinel prompt, completion, key, and provider
  error-body values. They assert the correct header/model request while proving
  none of the sentinels appears in thrown messages, safe response objects, or
  captured logs.
- A fast Temporal test fetches and serializes execution history after the real
  LLM activity runs against a fake provider/poster. The prompt/completion/key
  sentinels must be absent from history, Memo, Search Attributes, workflow
  result/query state, and activity failure details.
- The same fast test (or focused companions) inspects captured Logback events,
  `WorkflowRun`/operator-audit/Envers write arguments, and REST/MCP response
  envelopes for the sentinels. This acceptance evidence must run in the normal
  unit/Sonar lane; a Testcontainers-only integration test is not sufficient.
- Misconfiguration is covered through the existing controller/service path and
  an `@WebMvcTest` assertion for the standard envelope. No raw Spring/Temporal/
  provider exception reaches the client.

## Extensibility Seam

The stable seam is `(project, stage, tier, provider, model, configDigest)` plus
the domain `LlmProvider` request/result contract. Adding OpenAI or Ollama should
add one classpath adapter, its operational properties/secret binding, and its
provider-aware model/default validation inside the existing routing resolver;
it should not edit workflow orchestration, invent another project config block,
or add provider-native fields to Temporal contracts.

Prompt construction remains activity-specific and versioned by a safe template
identifier; do not add a generic prompt DSL/registry for the first call site.
Provider-specific options stay inside the adapter unless a genuinely shared
semantic (for example a bounded output-token limit) has multiple callers.
Future credential-reference or secret-mount support can replace environment
binding behind the same operational properties without changing project route
contracts.

## Gotchas And Anti-Patterns

- Do not let an unreviewed `.ground-control.yaml` diff choose the provider that
  receives that same branch's code or context.
- Do not conflate historical route label `claude`, Anthropic the API provider,
  Claude model ids, Codex the reviewer of record, and `parent/subagent/cli`
  agent placement.
- Do not route `runCodexReview` through an arbitrary provider and call the
  result Codex. ADR-027/ADR-031 reviewer-of-record and structured MCP review
  contracts remain authoritative until their explicit Temporal cutover.
- Do not inject an LLM provider into `ImplementActivitiesImpl` or hide provider
  access behind a generic HTTP client that defeats the ArchUnit rule.
- Do not add `llm:` alongside `routing:` in `.ground-control.yaml`, a Java copy
  of `CLAUDE_MODEL_BY_TIER`, a per-project API-key column, a no-op provider, or a
  silent global model default.
- Do not serialize prompt/completion types with Jackson/Temporal, put them in a
  JPA entity, rely on `@NotAudited` as a content-leak control, or let generated
  `toString()` expose them.
- Do not log or propagate raw `RestClient` exceptions, provider error bodies,
  response objects, headers, request maps, or configuration objects containing
  secrets.
- Do not confuse activity retries with review cycles, claim exactly once model
  billing, or use a retry/fallback to bypass a failed route/configuration
  decision.
- Do not copy `gc_post_implementation_plan` checks into Java, post with direct
  `gh`/`git`/`curl` from an agent sandbox, or let successful inference count as
  a completed plan phase before durable publication succeeds.
- Do not use `WorkflowRun`, Envers, Search Attributes, logs, audit rows, or a
  new transcript table as an LLM content store or execution state machine.

## Non-Goals

- No OpenAI/Ollama adapter in the Anthropic-first slice, dynamic provider
  plugins, provider marketplace, arbitrary provider endpoints from project
  config, or tenant-to-namespace mapping.
- No prompt/completion browser, transcript retention/search, model playground,
  public route-selection API, or per-user API keys.
- No new workflow DSL, prompt DSL, generic agent runtime, tool-execution API,
  content/blob store, exception hierarchy, auth filter, audit mechanism, error
  envelope, or telemetry state machine.
- No change to the one-human-touchpoint model, plan/GRC prerequisites,
  reviewer-of-record, review caps, merge observation, or ADR-081 cutover rules.
- No claim that phase 5 alone makes the complete `/implement` lane production
  ready; the trusted normalized-config/publication adapters still follow the
  ADR-081 bridge boundary, and starts remain fail-closed until they exist.
