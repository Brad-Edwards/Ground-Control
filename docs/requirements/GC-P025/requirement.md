---
id: GC-P025
title: "Runtime Metrics and Distributed Tracing"
status: DRAFT
type: NON_FUNCTIONAL
priority: MUST
wave: 8
created_at: 2026-07-02T22:17:57.752241Z
updated_at: 2026-07-02T22:17:57.752241Z
---

# GC-P025 — Runtime Metrics and Distributed Tracing

## Statement

The system shall be fully instrumented with runtime metrics and distributed tracing across the backend, workflow workers, and web console.

(a) Metrics. The backend and Temporal workers shall expose Micrometer-based metrics via a standard collection endpoint: HTTP request rates, latencies, and errors; JVM and connection-pool health; workflow activity durations, retries, and failures by activity type; gate wait times; and LLM provider call counts, token usage, and cost — labeled by project identifier where cardinality permits.

(b) Tracing. Distributed traces (OpenTelemetry) shall propagate across console → backend → worker → outbound adapters (GitHub, LLM providers), correlated with Temporal workflow IDs and run IDs so a workflow run's full causal chain is reconstructable from a single trace identifier.

(c) Dashboards. Operational dashboards shall surface run health, throughput, failure hotspots, and run economics. The workflow_run projection (ADR-061) remains the durable economics record; dashboards become the operational view.

(d) Redaction. Metrics, traces, and logs shall carry no prompts, completions, bearer tokens, provider API keys, or other secret material, consistent with ADR-028's observability constraints, and shall be verified by redaction tests.

(e) Deployment fit. The collection stack shall run within the existing on-prem deployment model (ADR-030/ADR-063) with documented resource bounds, retention, and backup policy coverage, and the metrics endpoint shall be access-controlled per ADR-026.

## Rationale

The transition to server-orchestrated development makes the dev workflow a production service; operating it without metrics or tracing is not acceptable. Today observability is Actuator health, JSON logs, Envers audit, and the run-economics projection — there is no metrics registry and no tracing pipeline in the backend build. GC-O009(g) relies on Temporal Visibility for workflow telemetry; this requirement supplies the platform instrumentation around it so failures, latency, and cost are observable end to end.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1285` (GC-P025: metrics and distributed tracing)
- DOCUMENTS → ADR `architecture/adrs/090-production-line-measurement-model.md` (ADR-090: bounded metric labels, separated outcome axes, and redaction constraints GC-P025 must consume)
