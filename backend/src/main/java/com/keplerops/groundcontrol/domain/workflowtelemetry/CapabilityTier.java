package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * ADR-036 provider-neutral capability tier carried by a durable step observation (ADR-090
 * amendment, issue #1354).
 *
 * <p>A canonical dimension of the production-line measurement model, matching the
 * {@code CapabilityTier} enum in {@code contracts/schemas/measurement/measurement-record.v1.schema.json}.
 * {@link #LOW}, {@link #MEDIUM}, and {@link #HIGH} keep their ADR-036 meanings; provider and model
 * are a separate dimension, so a model name is never accepted as a tier. {@link #NOT_APPLICABLE}
 * means no model choice applied; {@link #UNOBSERVED} is not evidence of {@link #LOW}.
 */
public enum CapabilityTier {
    LOW,
    MEDIUM,
    HIGH,
    NOT_APPLICABLE,
    UNOBSERVED
}
