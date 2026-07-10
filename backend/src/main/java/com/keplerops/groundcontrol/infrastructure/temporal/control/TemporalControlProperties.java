package com.keplerops.groundcontrol.infrastructure.temporal.control;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the workflow control surface (GC-O009 phase 3). Typed, startup-validated, and
 * environment-schema aware — no ad hoc env lookups in the adapter (ADR-028 / preflight note).
 *
 * <p>The control surface reuses the Temporal {@code WorkflowClient} created by the worker
 * configuration, so enabling it requires {@code groundcontrol.temporal.worker.enabled=true} as well
 * (the shared client is the Temporal connection).
 *
 * @param enabled register the Temporal control adapter (start/status/signal). Off by default so CI and
 *     dev boot without a Temporal server.
 * @param taskQueue the task queue new executions are started on. Left {@code null} when unset;
 *     {@code TemporalControlConfiguration} then falls back to the worker's task queue so started
 *     executions land on the running worker unless control deliberately overrides it.
 * @param defaultCompletionCommand the automation command the worker runs; server-side only, never
 *     caller-supplied.
 */
@ConfigurationProperties(prefix = "groundcontrol.temporal.control", ignoreUnknownFields = false)
public record TemporalControlProperties(boolean enabled, String taskQueue, String defaultCompletionCommand) {

    public TemporalControlProperties {
        // taskQueue is intentionally NOT defaulted here: an unset control queue must fall back to the
        // worker's configured queue (resolved in TemporalControlConfiguration), not a hard-coded value
        // that would diverge from a customized worker queue and leave started executions idle.
        taskQueue = taskQueue == null || taskQueue.isBlank() ? null : taskQueue.trim();
        defaultCompletionCommand = defaultIfBlank(defaultCompletionCommand, "make check");
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
