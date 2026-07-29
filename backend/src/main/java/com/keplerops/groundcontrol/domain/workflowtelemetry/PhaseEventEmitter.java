package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * Which emitter produced a {@link WorkflowPhaseEvent} (ADR-090 amendment, issue #1354).
 *
 * <p>The production-line measurement contract names four emitters, but only two can produce a
 * phase-event row: the ADR-061 workflow/lifecycle emitter and the ADR-036 per-step observer.
 * ADR-059 MCP-tool usage and OpenTelemetry runtime facts live in their own stores and never reach
 * this table.
 *
 * <p>The value is the discriminator that keeps a routed-step cost observation from impersonating a
 * station attempt: lifecycle hot-spot queries, yield/rework formulas, and the context-graph
 * projection consume only {@link #ADR061_WORKFLOW_TELEMETRY} rows, while the per-step surface
 * ({@code GET /{runId}/events} and SSE) exposes both. Rows written before this axis existed, and
 * every live lifecycle/station emission, are {@link #ADR061_WORKFLOW_TELEMETRY}.
 */
public enum PhaseEventEmitter {
    ADR061_WORKFLOW_TELEMETRY,
    ADR036_STEP_JSONL
}
