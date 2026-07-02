package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F009 / ADR-081 §3 — closed vocabulary for source roles in the
 * taxonomy-development method family. Taxonomy-instance corpus,
 * background/framing literature, methodology literature, and
 * validation/evaluation material must remain separate roles: background or
 * framing sources do not support recurrence, prevalence, coverage,
 * exhaustiveness, or taxonomy-validity claims unless a {@link
 * ProtocolPlanSection} explicitly assigns that evidentiary role. Only sections
 * of {@link ProtocolSectionKind#SOURCE_ROLES} on the {@code
 * taxonomy_development} method may carry a source role.
 *
 * <ul>
 *   <li>{@link #TAXONOMY_INSTANCE_CORPUS} — the corpus of instances the
 *       taxonomy classifies.</li>
 *   <li>{@link #BACKGROUND_FRAMING} — background or framing literature; does
 *       not evidence taxonomy claims.</li>
 *   <li>{@link #METHODOLOGY_LITERATURE} — literature describing the
 *       construction method itself.</li>
 *   <li>{@link #VALIDATION_EVALUATION} — material used to validate or evaluate
 *       the resulting taxonomy.</li>
 * </ul>
 */
public enum ProtocolSourceRole {
    TAXONOMY_INSTANCE_CORPUS,
    BACKGROUND_FRAMING,
    METHODOLOGY_LITERATURE,
    VALIDATION_EVALUATION
}
