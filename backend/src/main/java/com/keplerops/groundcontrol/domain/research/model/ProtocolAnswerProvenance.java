package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F008 / GC-RSCH-R002 / ADR-083 §4 — closed vocabulary classifying
 * where a {@link ProtocolCoverageDisposition#FILLED} answer came from. This
 * classification is not proof by itself: it tells later validation which
 * existing surface must support the answer. The backend does not treat model
 * memory, skill prose, or workspace-local file text as accepted evidence.
 *
 * <ul>
 *   <li>{@link #METHODOLOGY_SOURCE} — grounded in a methodology source or
 *       ADR-080 contract entry.</li>
 *   <li>{@link #RESEARCH_INTAKE} — grounded in the paper/research intake
 *       context.</li>
 *   <li>{@link #USER_DECISION} — grounded in a durable user decision.</li>
 *   <li>{@link #CITED_SOURCE} — grounded in a citable source resolved through
 *       the citation/Zotero boundary.</li>
 *   <li>{@link #DEFERRED_PILOT} — a pilot/emergent decision deferred to a later
 *       stage.</li>
 *   <li>{@link #ADAPTER_OUTPUT} — accepted through a structured adapter/tool
 *       service command.</li>
 * </ul>
 */
public enum ProtocolAnswerProvenance {
    METHODOLOGY_SOURCE,
    RESEARCH_INTAKE,
    USER_DECISION,
    CITED_SOURCE,
    DEFERRED_PILOT,
    ADAPTER_OUTPUT
}
