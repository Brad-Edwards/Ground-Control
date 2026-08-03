---
id: GC-Q016
title: "Workflow Operations and Agent Interaction Console"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 8
created_at: 2026-07-02T22:18:12.153896Z
updated_at: 2026-07-13T02:27:06.667315Z
---

# GC-Q016 — Workflow Operations and Agent Interaction Console

## Statement

The web application shall provide a workflow operations console over the development workflow, so an operator can understand what the agents are doing across every project the instance manages without leaving the product.

(a) Run visibility. Users shall see current and historical workflow runs — identity and scope (project, repository, issue, branch, PR, workflow type), recorded phase history, outcome, failures, and timing — scoped to the projects the user can access, across all projects the instance manages.

(b) Record reading. The console shall render the agent-produced durable records — plans, review findings, decision records, readiness and final reports — with the record of authority linked rather than duplicated, and shall bound or summarize any record too large to display safely.

(c) Cost and outcome. Users shall see the economics of a run and relate cost to outcome (merged PR, closed issue, review and CI cycles, escalations, wall-clock by phase).

(d) Honest state. Run state shall be presented as what was last reported, with the time it was reported, and shall never be presented as a live handle on an executing process that the console can steer.

(e) Instrumentation. Console interactions with runs shall be traceable end to end via GC-P025 correlation.

This requirement names no orchestration technology and assumes no execution-control surface. Whether the console can also *act* on a run — start, cancel, retry, or signal it — is a separate product decision that requires its own requirement and ADR; it is deliberately not in scope here.

## Rationale

Superseded 2026-07-13 (issue #1384). The original statement mandated a technology stack: it specified run visibility "sourced from Temporal Visibility per ADR-028's read model", gate actions on an "operator signal" set, and run start/cancel/retry "via the product workflow control surface, never via direct Temporal access". Issue #1359 removed the Temporal orchestration lane outright — no worker, namespace, task queue, operator-signal API, or workflow-execution control surface exists, and ADR-028/081/088 are superseded — which left this requirement specifying a console against machinery that had been deleted. A future agent could have implemented it faithfully and built against nothing.

A requirement should state the operator's need, not the implementation that happens to serve it; naming the stack is what let a technology retirement invalidate a product requirement. The statement is therefore rewritten in product terms and is now satisfiable on the surfaces that exist: the GitHub issue thread as the durable workflow record (ADR-029) and the ADR-061 workflow-run telemetry read-model as the queryable projection over it.

The run-control clauses are dropped rather than ported. They depended entirely on the deleted control surface, and reintroducing the ability to drive a run from the console is a genuine product decision, not a mechanical translation. Clause (d) is new, and exists because the telemetry model records what an agent last reported and never drives execution (ADR-061): a console that renders that as a live process handle would be a control plane with the controls painted on.

Original rationale retained for context: GC-O009(c) required workflow visibility through Ground Control's web UI, and the workflow-runs page is a read-only economics dashboard. Driving development of all projects through Ground Control needs an operations surface: watch runs, read agent records, and understand cost — without bypassing the product's authorization and audit boundary.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1284` (GC-Q016: workflow operations and agent interaction console)
