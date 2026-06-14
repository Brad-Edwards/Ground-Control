# ADR-028: Temporal Workflow Orchestration Boundary

## Status

Accepted

## Date

2026-05-03

## Context

GC-O009 moves the `/implement` loop from agent markdown into Temporal. This is
a product capability, not a worker-local automation script: workflow starts,
human gates, activity status, retries, failures, and telemetry must be visible
through Ground Control's REST API, MCP tools, and web UI.

The risk is not whether Temporal can run workflows. The risk is concept
confusion: duplicating workflow state in PostgreSQL, making Project equal
Tenant, adding a second workflow configuration schema, treating plugins as
dynamic code execution, bypassing existing REST/security/error conventions, or
letting LLM-backed activities leak into deterministic orchestration code.

Existing decisions already define much of the boundary:

- ADR-013 defines the Spring Boot / ports-and-adapters layering.
- ADR-016 defines project scoping and explicitly says it is not SaaS
  multi-tenancy.
- ADR-021 defines the gated `/implement` contract.
- ADR-023 defines plugin registry semantics and says existing adapter
  contracts are not automatically plugins.
- ADR-026 defines API access control and the error envelope.
- ADR-027 defines the interim agent-neutral workflow packaging and the
  `.ground-control.yaml` configuration boundary.

## Decision

Adopt Temporal as an orchestration adapter around existing Ground Control
domain services, not as a second domain model.

### Layering

Temporal code follows the existing package boundary:

```
api/ -> domain/ <- infrastructure/
```

Workflow-facing REST controllers and DTOs live under `api/`. Durable workflow
use cases, command records, project resolution, gate authorization decisions,
and workflow configuration validation live in `domain/`. Temporal client,
worker, activity implementations, LLM providers, GitHub/Git adapters, and
Temporal visibility adapters live in `infrastructure/`.

Domain services must not import Temporal SDK classes. Controllers must not
call repositories or Temporal workers directly. Activities call domain services
or infrastructure ports; workflow definitions call activities and receive
signals.

### Workflow State And Visibility

Temporal history is the source of truth for execution progress, retries,
activity failures, and signal receipt. Ground Control must not create a
parallel PostgreSQL state machine that attempts to mirror every Temporal event.

PostgreSQL may store product configuration and stable correlation records
needed by Ground Control, such as project workflow configuration, LLM provider
selection, workflow execution IDs, requirement/issue anchors, and user-facing
labels. Mutable product configuration uses the existing JPA/Flyway/BaseEntity
pattern and Envers where auditability matters.

REST, MCP, and UI visibility queries should read from Temporal Visibility plus
Ground Control correlation/configuration data. If the product later needs
longer-retention reporting than Temporal Visibility provides, add an explicit
read-model projection with its own retention semantics; do not use that
projection to drive workflow execution.

### Project Scoping And Tenancy

Every workflow execution is scoped to a Ground Control project using
`ProjectService` resolution and project-scoped repository queries. Project
identifier, requirement UIDs, issue number, workflow type, and outcome must be
recorded as Temporal Search Attributes where Temporal supports querying them.

Project scoping is not tenant isolation. Until a separate tenant model exists,
use one Ground Control Temporal namespace and partition workflow IDs and Search
Attributes by project. Do not create a Temporal namespace per `Project`; that
would confuse project-level requirements management with infrastructure tenancy.

When SaaS tenant isolation is introduced, tenant-to-Temporal-namespace mapping
requires its own ADR. Project-scoped workflow IDs remain valid inside a tenant
namespace.

### Workflow Configuration

GC-O009 consumes the configuration shape introduced by ADR-027. Do not add a
second workflow DSL or parse activity lists from skill prose. Configurable
steps are structured named activities with validated inputs, defaults, and
project-scoped overrides.

Activity replacement means selecting a registered, classpath-available
implementation by stable name. ADR-023's `PluginRegistry` is metadata and
lifecycle inventory; it is not permission to download or execute arbitrary
workflow code. Dynamic code loading, trust policy for executable workflow
extensions, or marketplace installation require a later ADR.

### LLM Provider Boundary

LLM-backed activities use a domain port and infrastructure provider adapters,
following the existing `EmbeddingProvider` pattern without copying its API
shape. Provider selection is project-configurable, but secrets are not stored
in plaintext workflow histories, Temporal Search Attributes, REST responses,
MCP responses, logs, or Envers audit rows.

Deterministic activities such as issue resolution, Git operations,
traceability reconciliation, quality-gate evaluation, and requirement status
transitions must not depend on an LLM provider.

### Temporal Determinism

Workflow definitions must stay deterministic. They must not call Spring
repositories, REST clients, GitHub CLI, Git commands, filesystem operations,
random generators, wall-clock APIs, or LLM providers directly. Those effects
belong in activities.

Workflow and activity input/output records are versioned API contracts. Avoid
passing JPA entities, request DTOs, exceptions, secrets, or provider-native
responses through Temporal history.

### Human Gates

Per ADR-029 the workflow has exactly one synchronous human gate: PR merge.
Merge ratification is not a Temporal signal - GitHub's merge action is the
authoritative event, observed by the Temporal workflow via webhook or polling.
Signal endpoints (if introduced for forward-looking gates such as long-running
review-finding decisions or operator overrides) use existing
authentication/authorization, Bean Validation, `ProjectService` scoping, and
`GlobalExceptionHandler` error envelopes. No gate signal may be accepted for a
workflow outside the caller's project scope.

Plan and review-finding decisions are recorded as comments on the GitHub issue
thread (per ADR-029); they are durable transports, not Temporal signals. The
Temporal workflow may read them for observability but does not block on
synchronous human input for plan approval - that gate was removed by ADR-029
before Temporal lands.

If GC-O009's draft requirement text, issue prose, or older workflow notes refer
to "plan approval" as a Temporal signal, that wording is stale with respect to
ADR-029 and must not drive implementation. GC-O009 may introduce signal
endpoints only for gates that still exist in the accepted workflow contract
or for explicit operator controls whose authorization, audit record, and
failure semantics are defined in Ground Control. Do not reintroduce a
synchronous plan-approval gate as a side effect of adopting Temporal.

The transition bridge from the existing `/implement` skill must keep using the
MCP-side issue-thread marker family for preflight, plan, review-cycle, and
verify-cycle enforcement until Temporal owns those phases end to end. Bridge
code may start workflows and send supported signals, but it must not become a
second workflow engine with independent counters, phase state, or gate rules.

### Observability And Security

Temporal Web and gRPC endpoints are infrastructure surfaces. They must not be
exposed publicly as the product UI or relied on as the only authorization
boundary.

Application logs continue to use SLF4J/Logback and MDC. Workflow and activity
logs should include safe correlation fields such as project identifier,
workflow ID, run ID, activity type, attempt, and requirement UID. Do not log
prompts, completions, bearer tokens, provider API keys, GitHub tokens, or raw
approval text unless a later privacy/security ADR explicitly allows it.

New REST endpoints must fit ADR-026's path matrix. Administrative worker or
namespace operations require `ROLE_ADMIN`; ordinary project workflow reads and
human gate signals require an authenticated caller and project scope checks.

### Testing And Policy

Temporal workflow tests use Temporal's Java test environment for replay,
signals, retries, and crash/resume behavior. Activity tests are ordinary unit
or slice tests around one activity class with typed inputs/outputs. Controllers
keep the existing `@WebMvcTest` expectation. Persistence changes use Flyway
migrations and migration smoke tests. Architecture rules must continue to
enforce the `api/ -> domain/ <- infrastructure/` dependency boundary.

## Consequences

### Positive

- Temporal durability is adopted without replacing Ground Control's domain
  services, project scoping, REST contracts, or quality gates.
- Workflow visibility can use Temporal's native history and search attributes
  instead of a fragile duplicate state table.
- Project-level workflow partitioning is available now without pretending it is
  full SaaS tenant isolation.
- LLM use is isolated to explicitly LLM-backed activities.

### Negative

- Product visibility depends on operating Temporal Visibility correctly.
- A future SaaS tenant model will need a separate namespace/isolation decision.
- Configurable workflow steps are intentionally limited to classpath-available
  implementations until executable plugin trust is designed.

### Risks

- If REST/MCP/UI code drives behavior from a PostgreSQL projection instead of
  Temporal history, crash recovery and retry semantics can diverge.
- If workflow definitions call Spring services or external APIs directly,
  Temporal replay can become nondeterministic.
- If project scoping is marketed or coded as tenant isolation, cross-customer
  isolation may be overstated.
- If prompts or secrets enter Temporal history or Search Attributes, they
  become durable operational data and are hard to scrub.

## Non-Goals

- Defining a new SaaS tenant model.
- Creating a workflow DSL beyond ADR-027's configuration shape.
- Implementing dynamic executable plugins or marketplace-loaded activities.
- Replacing quality gates, traceability services, verification results, or the
  existing `/api/v1/**` error/security contract.
- Treating Temporal Web as the Ground Control workflow UI.

## Related Requirements

- GC-O009 Workflow Orchestration via Temporal
- GC-O007 Gated Agentic Development Loop

## Related ADRs

- ADR-013 Java/Spring Boot Backend Rewrite
- ADR-016 Project Scoping
- ADR-021 Gated Agentic Development Loop
- ADR-023 Plugin Architecture
- ADR-026 REST API Access Control
- ADR-027 Agent-Neutral Implement Workflow Packaging
