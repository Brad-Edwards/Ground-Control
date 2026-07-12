package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-R005 / ADR-086 §3 — approval state of a research high-risk operation
 * authorization record. A request lands {@code PROPOSED}; an authenticated
 * admin/operator decision moves it to {@code APPROVED} or {@code DENIED}
 * ({@code AUTONOMOUS} runs may propose but never approve). A one-time-use
 * {@code APPROVED} record is spent to {@code CONSUMED} by the executor and moves
 * to {@code EXPIRED} once past its expiry.
 */
public enum ResearchOperationAuthorizationState {
    PROPOSED,
    APPROVED,
    DENIED,
    CONSUMED,
    EXPIRED
}
