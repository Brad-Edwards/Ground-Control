package com.keplerops.groundcontrol.domain.llm;

/**
 * Result of {@link PlanPublicationPort#publish(PlanPublicationRequest)}: bounded reference facts only
 * — never plan prose. Mirrors the shape of the Temporal {@code AuthorPlanResult} contract record; the
 * infrastructure activity converts between the two at the Temporal boundary so the domain port stays
 * free of Temporal/Jackson types.
 */
public record PlanPublicationResult(boolean posted, Integer commentId) {}
