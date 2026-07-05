# Temporal Infrastructure Topology Preflight

Issue #1276 is the phase-1 infrastructure slice for GC-O009. It should add the
Temporal server, visibility persistence, worker topology, deploy fit, backup
coverage, and architecture enforcement needed before the actual `/implement`
workflow is implemented. This note is architecture guidance only; it does not
implement Temporal workflows, activities, REST control endpoints, MCP tools, UI,
or LLM provider routing.

## Boundary Decisions

- Treat Temporal as infrastructure and an orchestration adapter, not as a new
  domain model. ADR-028 remains binding: workflow progress and retries live in
  Temporal history/visibility; PostgreSQL only stores Ground Control
  configuration and correlation records that the product owns.
- Keep a single Temporal namespace. Project partitioning is by workflow ID and
  Search Attributes; project scoping is not tenant isolation, and a namespace
  per project is out of scope until a future tenancy ADR exists.
- Keep Temporal SDK dependencies out of `domain/`. Temporal client, worker,
  workflow definitions, activity implementations, Temporal visibility adapters,
  and SDK-specific retry options live in `infrastructure/`; `api/` controllers
  and `domain/` services deal in Ground Control command/response records.
- Keep Temporal persistence out of Ground Control's JPA/Flyway application
  schema. Temporal owns its internal SQL schema and migration mechanism. If the
  deployment shares the same Postgres container, use isolated database/schema
  names and credentials; if it uses a separate Postgres container, keep that
  container inside the same deploy, backup, and restore policy surface.
- Treat Temporal Web and gRPC as infrastructure endpoints. They are not the
  Ground Control workflow UI and must not be exposed publicly or relied on as an
  authorization boundary.
- A minimal durable workflow used to prove worker restart behavior must stay a
  smoke/probe workflow unless its payloads are published under the ADR-082
  contract surface. Do not let a proof workflow become the `/implement` engine
  by accident.

## Cross-Cutting Concerns to Reuse

- **Deploy artifacts:** extend `deploy/docker/docker-compose.prod.yml`,
  `deploy/docker/env.schema`, `deploy/docker/validate-env.sh`,
  `deploy/docker/deploy.sh`, `deploy/docker/MANIFEST.sha256`,
  `scripts/deploy.sh`, `make deploy-manifest`, `make deploy-status`, and
  `tools/policy/checks.py::run_deploy_artifact_consistency`. Do not add a
  second deploy wrapper or a compose-only drift check.
- **Development compose:** extend the root `docker-compose.yml` with the same
  service names, health posture, and localhost-only bind discipline appropriate
  for dev. Keep dev bind choices distinct from production tailnet binds.
- **Backup and restore:** extend `deploy/scripts/backup.sh`,
  `deploy/scripts/test-restore.sh`, `deploy/scripts/install-gc-backup.sh`,
  `deploy/systemd/gc-backup.*`, `deploy/systemd/gc-restore-test.*`,
  `scripts/assert-backup-policy.sh`, and the backup runbook so Temporal
  persistence is part of the GC-P021 evidence bundle.
- **Backend layering:** extend `ArchitectureTest` for "no Temporal SDK in
  domain" and keep the existing `api/ -> domain/ <- infrastructure/` rules.
  Controllers remain thin; services own transaction/domain orchestration;
  repositories own Ground Control queries.
- **Configuration:** add Spring configuration through
  `@ConfigurationProperties` classes registered by `@ConfigurationPropertiesScan`
  and validate them at startup. Deployment-facing values must be declared in
  `env.schema`; do not parse `/opt/gc/.env` directly in Java or source it from
  shell validators.
- **Project scoping:** use `ProjectService.requireProject*` /
  `resolveProject*` and project-scoped repositories for product-facing actions.
  Workflow IDs and Search Attributes should carry safe project, requirement,
  issue, workflow type, and outcome fields only.
- **Errors and validation:** use Bean Validation on REST request records,
  immutable command records for service inputs, `GroundControlException`
  subclasses for expected failures, and `GlobalExceptionHandler` /
  `ErrorResponse` for HTTP responses.
- **Logging and audit:** use SLF4J/Logback with MDC correlation fields such as
  project, workflow ID, run ID, task queue, activity type, attempt, requirement
  UID, and issue number. User-triggered REST actions get actor provenance from
  `ActorFilter` / `ActorHolder`; workers should use system/worker identity
  fields rather than spoofing a user actor.
- **Workflow telemetry:** reuse the ADR-061 workflow-run surface only as a
  correlation/projection read model. Once Temporal is authoritative, ingest or
  query Temporal Visibility; do not drive execution from `workflow_run`.
- **Tests:** keep controller coverage in `@WebMvcTest` slices, activity/worker
  logic in focused unit tests, Temporal replay/retry/restart behavior in
  Temporal's Java test environment, deploy/policy invariants in
  `tools/tests/test_policy.py`, and schema/migration checks in migration smoke
  tests where Ground Control-owned tables are added.

## Security Layers In Scope

- **Endpoint exposure:** production Temporal gRPC/Web ports must be internal to
  the compose network or bound only to the tailnet address when an operator
  surface is deliberately needed. Development ports should bind to
  `127.0.0.1`. Never publish Temporal Web as the product console.
- **REST authorization:** any workflow start/status/signal endpoints added by a
  later phase must pass the shared bearer/session `ApiPathMatrix`, project
  scope checks, Bean Validation, and the `ErrorResponse` envelope. Operator
  controls remain `ROLE_ADMIN` until a finer gate-authority model lands.
- **Env binding:** every production compose `${VAR}` must be declared in
  `env.schema` and validated by `validate-env.sh` without sourcing the file or
  printing secret values. Spring-side Temporal properties need their own
  startup validation so bad namespace, endpoint, task-queue, or credential
  values fail before a worker starts polling.
- **Secret handling:** Temporal SQL passwords, API tokens, LLM provider keys,
  GitHub tokens, prompts, completions, and reviewer payloads must not enter
  Temporal workflow history, Search Attributes, logs, REST/MCP responses,
  deploy-state JSON, GitHub Deployment descriptions, or process argv.
- **Backup identity:** keep `gc-backup` separate from `gc-deploy`. Extending
  backups for Temporal persistence must not require `gc-backup` to read
  `/opt/gc/.env`; if a separate Temporal credential is unavoidable, document it
  as a distinct backup secret boundary and keep validation/logging name-only.
- **OS/process exposure:** deploy, rollback, health checks, and backup scripts
  should pass commands as argv arrays where applicable and avoid embedding
  secrets in command lines, shell history, Docker health output, or journals.
- **Temporal payloads:** workflow/activity records are durable operational data.
  Pass IDs, enum values, bounded scalars, and redacted summaries; do not pass
  JPA entities, Spring request DTOs, exceptions, provider-native responses, raw
  issue comments, prompts, completions, or secrets through Temporal history.

## Maintainability Guardrails

- Add the Temporal dependency and version pins deliberately, with Gradle lockfile
  updates, rather than relying on transient dependency resolution.
- Keep the worker as a product process with explicit configuration: namespace,
  target endpoint, task queue, identity, enabled flag, and safe health/readiness
  behavior. A worker service may reuse the backend image if the command/profile
  is explicit, but it should not start hidden inside the web process without a
  clear operational switch.
- Keep deploy health checks centralized in `deploy/docker/deploy.sh`. The
  operator wrapper may sync artifacts and publish deploy status, but it must not
  duplicate `docker compose pull/up`, health polling, or rollback logic.
- Keep Temporal persistence backup evidence structural and testable. Updating
  only the runbook is not enough; `make policy` must fail if Temporal
  persistence falls out of the backup/restore artifact set.
- Keep Temporal visibility and ADR-061 telemetry conceptually separate.
  Visibility answers "what did Temporal execute"; the workflow-run table answers
  "what product reporting projection have we ingested."
- Keep activity registration by stable names and classpath-available
  implementations. Do not treat ADR-023 plugin metadata as permission for
  dynamic workflow code loading.

## Extensibility Seams

- Parameterize Temporal topology by namespace, endpoint, task queue, identity,
  persistence database/schema/user, visibility database/schema/user, retention
  knobs, and worker enabled flag. These belong in config, not scattered in
  code, scripts, and compose comments.
- Parameterize workflow partitioning by project identifier plus stable workflow
  type and run identifiers. A future tenant-to-namespace mapping should add a
  tenant namespace resolver without rewriting project-scoped workflow IDs.
- Parameterize the activity registry by stable activity name and implementation
  bean. This preserves project-level step replacement without enabling dynamic
  code execution.
- Keep LLM provider selection behind a provider port and project-scoped
  configuration in later phases. Deterministic activities in phase 2 must not
  depend on this seam.
- If Temporal Visibility retention is too short for product reporting later,
  add an explicit projection with retention/rebuild semantics. That projection
  must never become the execution driver.

## Gotchas and Anti-Patterns

- Do not create a PostgreSQL workflow state machine that mirrors Temporal event
  history and then drives behavior from the mirror.
- Do not create a Temporal namespace per Ground Control project.
- Do not bind Temporal Web or gRPC to `0.0.0.0` in production.
- Do not put Temporal SDK imports in `domain/`, Spring Web DTOs in workflows, or
  JPA entities/exceptions in Temporal payloads.
- Do not store prompts, completions, provider keys, GitHub tokens, bearer
  tokens, raw reviewer payloads, or raw issue comments in Temporal history,
  Search Attributes, logs, or telemetry projections.
- Do not make the existing `/implement` skill bridge a second workflow engine
  with its own counters, phase state, or gate rules. Until cutover, the
  issue-thread marker family remains authoritative for bridge phases.
- Do not reintroduce plan approval or model PR merge as a Temporal signal.
  GitHub merge is the only synchronous human gate and is observed as an
  external authoritative event.
- Do not weaken deploy rollback, env validation, release-pin validation,
  tailnet bind posture, or backup restore verification to make the new services
  start.

## Non-Goals

- No implementation of the `/implement` Temporal workflow, typed activity set,
  LLM provider API, operator signal surface, MCP bridge, or console UI in this
  phase.
- No SaaS tenant model, tenant-to-namespace mapping, public Temporal console,
  dynamic executable plugins, or marketplace-loaded activities.
- No replacement for ADR-029 issue-thread durable records during the transition
  bridge.
- No new backend release/deploy domain entity or database-backed deploy state.
  Deployment state remains `/opt/gc/deploy-state.json` plus GitHub Deployments.
- No new error envelope, auth scheme, actor source, env parser, backup identity,
  GitHub client, or workflow telemetry store.
