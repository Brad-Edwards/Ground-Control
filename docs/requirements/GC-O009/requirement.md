---
id: GC-O009
title: "Workflow Orchestration via Temporal"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 3
created_at: 2026-04-13T00:35:10.831988Z
updated_at: 2026-07-12T04:52:22.854955Z
---

# GC-O009 — Workflow Orchestration via Temporal

## Statement

Ground Control shall adopt Temporal as its workflow orchestration engine, replacing the current monolithic markdown skill with a durable, testable, project-scoped workflow infrastructure that is part of the product surface.

The /implement agentic development loop (GC-O007) shall be re-implemented as a Temporal workflow with typed activities, where each activity is a Java class with defined inputs, outputs, retry policies, and unit tests. Activities that require LLM reasoning shall call an LLM via a configurable provider API (not bound to any specific IDE or agent runtime). Activities that are deterministic (issue resolution, git operations, traceability reconciliation, requirement status transitions) shall be pure API orchestration with no LLM dependency.

The system shall support:
(a) Durable execution — workflow state survives worker crashes, application restarts, and deployments without manual recovery.
(b) Human gates — exactly one synchronous human gate: PR merge (ADR-029). GitHub's merge action is the authoritative event, observed by the workflow via webhook or polling, never modeled as a Temporal signal. Operator signals (cancel, retry-from, and the review-cap dispositions the GC-O007 contract defines) are explicit, contract-versioned signals sendable via MCP tools and REST API, requiring authenticated gate authority, project-scope checks, and audit records. No synchronous plan-approval gate exists.
(c) Workflow visibility — every workflow execution, activity completion, retry, and failure queryable via REST API, MCP tools, and Ground Control's web UI.
(d) Project-scoped isolation — workflow executions scoped to Ground Control projects via workflow-ID and Search-Attribute partitioning within a single Temporal namespace (ADR-028). Project scoping is not tenant isolation; tenant-to-namespace mapping is deferred to a future tenancy ADR.
(e) Configurable workflow steps — projects may enable, disable, or replace workflow activities (for example, skip SonarCloud, use a different review tool) via per-project configuration.
(f) Configurable LLM provider — activities that call an LLM shall support pluggable providers (Anthropic, OpenAI, local models via Ollama, etc.) per project.
(g) Workflow telemetry — activity durations, retry counts, finding counts, and outcomes captured by Temporal's built-in visibility and queryable for operational analysis.

The Temporal server shall run as Docker infrastructure alongside Ground Control's existing stack (Spring Boot + PostgreSQL + AGE). A transition bridge shall allow the existing /implement skill to trigger Temporal workflows and send signals, preserving the current UX while the backend matures.

## Rationale

The /implement skill encodes Ground Control's core value proposition — the gated development lifecycle from requirement to shipped, traceable code. As Ground Control becomes a SaaS platform, this workflow is a product feature, not a personal devtool. It must be testable (unit tests per activity), isolated (activities are independent Java classes), portable (not bound to Claude Code or any specific IDE), durable (survives crashes), multi-tenant (customers run independent workflows), and observable (execution history is a product surface). The current implementation — a 400-line markdown file interpreted by a single LLM in a single chat session — cannot provide any of these properties. Temporal is the right substrate because: (1) workflows-as-code in Java fits the existing Spring Boot stack, (2) durable execution with replay eliminates the crash-recovery problem, (3) signals provide native human-gate support, (4) namespaces provide multi-tenant isolation, (5) the visibility API provides workflow telemetry without custom instrumentation, and (6) MIT/Apache-2.0 licensing has no SaaS restrictions.

Amended 2026-07-03 (issue #1271, ADR-081): clause (b) rewritten to match the ADR-029 gate model — the original "plan approval and merge approval implemented as Temporal signals" predated ADR-029's removal of the synchronous plan-approval gate, and ADR-028 explicitly flags that wording as stale and forbids driving implementation from it. Clause (d) and the opening sentence rewritten from "multi-tenant" to project-scoped per ADR-028's decision (single namespace, workflow-ID/Search-Attribute partitioning by project; tenant-to-namespace mapping requires its own future ADR). Rationale point (4) reflects the original multi-tenant framing and is retained as historical context only.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `1279`
- DOCUMENTS → ADR `architecture/adrs/028-temporal-workflow-orchestration-boundary.md` (ADR-028: Temporal Workflow Orchestration Boundary)
- DOCUMENTS → ADR `architecture/adrs/036-per-step-routing-tool-surfaces-telemetry.md` (ADR-036: SKILL/MCP-level precursor to GC-O009 Temporal activities)
- DOCUMENTS → PULL_REQUEST `935` (PR #935 — MCP tools shaped like future Temporal activities)
- DOCUMENTS → DOCUMENTATION `architecture/notes/implement-thin-orchestrator-server-side-loops-preflight.md` (Issue #934 preflight — Temporal-compatibility boundary properties)
- DOCUMENTS → GITHUB_ISSUE `#529` (Workflow orchestration: adopt Temporal as first-class product infrastructure)
- DOCUMENTS → GITHUB_ISSUE `794` (Enforce workflow caps and ordering at the tool layer, not in skill prose)
- DOCUMENTS → GITHUB_ISSUE `859` (Surface /implement run economics and workflow telemetry)
- DOCUMENTS → GITHUB_ISSUE `868` (/implement cost: SKILL/MCP-level precursor to GC-O009 Temporal activities)
- DOCUMENTS → PULL_REQUEST `869` (SKILL/MCP-level precursor for GC-O009 (deterministic Temporal-shaped activities))
- DOCUMENTS → ADR `architecture/adrs/081-temporal-dev-workflow-and-console-program.md` (ADR-081: Temporal Dev Workflow and Console Program)
- IMPLEMENTS → GITHUB_ISSUE `1276` (Temporal infrastructure: server, visibility, worker topology, and deploy fit)
- IMPLEMENTS → PULL_REQUEST `1323` (added: temporal infrastructure topology)
- IMPLEMENTS → POLICY `tools/policy/checks.py` (Temporal topology policy gate)
- IMPLEMENTS → DOCUMENTATION `architecture/notes/temporal-infrastructure-topology-preflight.md` (Temporal infrastructure topology architecture preflight)
- IMPLEMENTS → GITHUB_ISSUE `1277` (Deterministic core workflow: typed activities and replay tests)
- IMPLEMENTS → PULL_REQUEST `1337` (added: deterministic core /implement Temporal workflow (GC-O009 phase 2))
- IMPLEMENTS → GITHUB_ISSUE `1278` (Workflow control surface: REST and MCP start/status/signal)
- IMPLEMENTS → ADR `architecture/adrs/088-temporal-human-gates.md`

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CONFIG `docker-compose.yml` (Local Temporal server, persistence, and worker topology)
- IMPLEMENTS → CONFIG `deploy/docker/docker-compose.prod.yml` (Production Temporal server, persistence, worker, and tailnet bind topology)
- IMPLEMENTS → CONFIG `deploy/docker/env.schema` (Required production Temporal environment schema)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/TemporalWorkerConfiguration.java` (Temporal service stubs, client, worker factory, and worker registration configuration)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/TemporalWorkerProperties.java` (Typed Temporal worker configuration properties)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/smoke/TemporalSmokeWorkflow.java` (Temporal smoke workflow contract)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/smoke/TemporalSmokeWorkflowImpl.java` (Temporal smoke workflow implementation)
- IMPLEMENTS → CODE_FILE `deploy/docker/deploy.sh` (Deploy-time Temporal frontend and worker health checks)
- IMPLEMENTS → CODE_FILE `deploy/scripts/backup.sh` (Temporal database backup capture)
- IMPLEMENTS → CODE_FILE `deploy/scripts/test-restore.sh` (Temporal database restore verification)
- IMPLEMENTS → POLICY `scripts/assert-backup-policy.sh` (Temporal backup/restore policy assertion)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/temporal/TemporalWorkerConfigurationTest.java` (Temporal worker configuration tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/temporal/TemporalSmokeWorkflowTest.java` (Temporal smoke workflow restart/durability test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/architecture/ArchitectureTest.java` (Temporal dependency boundary architecture test)
- TESTS → TEST `tools/tests/test_validate_env.py` (Temporal environment schema validation tests)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/implement/ImplementWorkflowImpl.java` (Deterministic /implement A–E workflow orchestration (GC-O009 phase 2))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/implement/ImplementActivitiesImpl.java` (Typed deterministic /implement activities over domain services + ports (GC-O009 phase 2))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/implement/ImplementWorkflow.java` (/implement Temporal workflow interface: run + operator signals + queries)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/implement/ImplementActivities.java` (Deterministic /implement activity interface (typed I/O contracts))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/temporal/implement/ImplementWorkflowReplayTest.java` (Temporal test-env: full phase graph, gate order, replay, signals, retry, crash/resume)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/temporal/implement/ImplementActivitiesImplTest.java` (Per-activity unit tests (typed I/O, non-retryable domain failures, observe-before-create))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/temporal/implement/WorkflowContractConformanceTest.java` (Activity payload record↔schema conformance + enum vocabulary parity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/workflowexecution/WorkflowExecutionController.java` (WorkflowExecutionController (REST start/status/signal))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/workflowexecution/service/WorkflowExecutionService.java` (WorkflowExecutionService (auth/scope/audit boundary))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/control/TemporalWorkflowControlAdapter.java` (TemporalWorkflowControlAdapter (Visibility + signals))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-workflow-execution.js` (gc_workflow_execution MCP tool)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/WorkflowExecutionControllerTest.java` (WorkflowExecutionControllerTest (@WebMvcTest slice))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/workflowexecution/WorkflowExecutionServiceTest.java` (WorkflowExecutionServiceTest (scope/auth/signal validation))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/temporal/control/TemporalWorkflowControlAdapterTest.java` (TemporalWorkflowControlAdapterTest (start/signal/describe mapping))
- TESTS → TEST `mcp/ground-control/gc-workflow-execution.test.js` (gc-workflow-execution MCP adapter tests)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/temporal/implement/port/MergeObservationPort.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/github/GitHubMergeObservationAdapter.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/workflowexecution/audit/OperatorSignalAudit.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/github/GitHubMergeObservationAdapterTest.java`
