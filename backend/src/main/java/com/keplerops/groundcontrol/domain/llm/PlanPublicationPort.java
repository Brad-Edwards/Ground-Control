package com.keplerops.groundcontrol.domain.llm;

/**
 * Publishes an in-process LLM plan completion to the durable issue-thread record (ADR-029/ADR-036 MCP
 * marker surface), returning only the bounded {@code (posted, commentId)} fact. This port never
 * receives or returns plan prose across a durable boundary; the completion argument is consumed
 * in-process and discarded.
 *
 * <p>A conforming implementation reuses {@code gc_post_implementation_plan} semantics (preflight/GRC
 * prerequisites, GRC deliverable coverage, reserved-marker rejection, {@code
 * detectSensitiveBodyContent}, body bounds, the plan phase marker) rather than recreating a weaker
 * Java-side rule set. Until the ADR-081 bridge (#1281) adapts that capability for Java callers, the
 * only production implementation fails closed with {@link
 * com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException} — publication is
 * unavailable, never silently replaced by a direct {@code gh} call.
 */
public interface PlanPublicationPort {

    /**
     * The observe half of observe-before-create. Temporal activities are at-least-once, so an activity
     * that infers first and publishes second will pay for a second inference on every retry — including
     * the retry that follows a publication whose acknowledgement was merely lost. Callers MUST consult
     * this before invoking the provider and skip inference entirely when a plan already exists for the
     * idempotency key.
     *
     * <p>This does not make inference exactly-once (a worker can still crash between a successful
     * provider call and a durable write); it closes the far larger duplicate-cost window where the plan
     * was already published and the activity is simply running again.
     */
    java.util.Optional<PlanPublicationResult> findExistingPlan(PlanPublicationObservation observation);

    PlanPublicationResult publish(PlanPublicationRequest request);
}
