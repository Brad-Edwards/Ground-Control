package com.keplerops.groundcontrol.domain.llm;

/**
 * The closed safe scalar set identifying an already-published plan record. Carries no prompt,
 * completion, or plan prose — it is the observe half of the observe-before-create contract on
 * {@link PlanPublicationPort}, so it must be cheap and safe to construct before any inference happens.
 */
public record PlanPublicationObservation(String project, int issueNumber, String idempotencyKey) {}
