package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F008 / ADR-081 §2 — closed vocabulary for how a {@link ProtocolPlan}
 * resolves one ADR-080 {@code REQUIREMENT} or {@code OPEN_PROTOCOL_QUESTION}
 * contract entry. Every current entry of those two kinds must have exactly one
 * disposition; the set is API-visible and follows ADR-034 enum-contract rules.
 *
 * <ul>
 *   <li>{@link #FILLED} — the plan provides a bounded answer with an {@link
 *       ProtocolAnswerProvenance} classification.</li>
 *   <li>{@link #RESOLVED_BY_USER_DECISION} — the answer depends on a durable
 *       user decision recorded through the existing gate-decision/rationale
 *       surfaces.</li>
 *   <li>{@link #DEFERRED_NON_BLOCKING} — the plan explicitly defers the answer
 *       to a later stage, with rationale and the deferred-to stage.</li>
 *   <li>{@link #NOT_APPLICABLE_WITH_RATIONALE} — the selected method/profile
 *       does not require the entry for this run, with bounded rationale.</li>
 *   <li>{@link #BLOCKING_DECISION_REQUIRED} — the plan cannot be accepted as an
 *       active protocol until a decision is made; a plan with any entry in this
 *       state blocks the {@code SOURCE_SEARCH} stage from starting.</li>
 * </ul>
 */
public enum ProtocolCoverageDisposition {
    FILLED,
    RESOLVED_BY_USER_DECISION,
    DEFERRED_NON_BLOCKING,
    NOT_APPLICABLE_WITH_RATIONALE,
    BLOCKING_DECISION_REQUIRED
}
