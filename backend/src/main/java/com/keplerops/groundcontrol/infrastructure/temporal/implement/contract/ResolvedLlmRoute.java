package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/**
 * Temporal contract payload mirroring {@link com.keplerops.groundcontrol.domain.llm.ResolvedLlmRoute}:
 * the closed safe scalar set (contract version, project, stage, tier, canonical provider id, canonical
 * model id, config digest) that is the only LLM-related shape allowed to cross the Temporal boundary.
 * No endpoint, credential, prompt, or provider options. Kept as a distinct type from the domain record
 * (rather than reused directly) so the domain layer stays free of Temporal/Jackson serialization
 * concerns; {@code TemporalWorkflowControlAdapter} and {@code ImplementContentActivitiesImpl} convert
 * between the two at the infrastructure boundary.
 *
 * <p>Activity payload. Schema: {@code gc.workflow.content-activities.v2#/$defs/ResolvedLlmRoute}
 * (reused inline, not via cross-schema $ref, on {@code gc.workflow.implement-workflow.v1#/$defs/
 * ImplementWorkflowInput#/properties/route}).
 */
public record ResolvedLlmRoute(
        String contractVersion,
        String project,
        String stage,
        String tier,
        String providerId,
        String modelId,
        String configDigest) {}
