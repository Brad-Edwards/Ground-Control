package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ProtocolSectionKind;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * GC-RSCH-F009 / ADR-081 §3 — the method-specific protocol output shape: which
 * {@link ProtocolSectionKind}s a {@link com.keplerops.groundcontrol.domain.research.model.ProtocolPlan}
 * must include for a given catalog method key. This is the extension seam the
 * ADR calls for: adding a method or variation is a data addition here, not a
 * new controller, MCP tool, repository, or table.
 *
 * <p>Method keys mirror {@code skills/lit-review/methodology/catalog.yaml}
 * ({@code scoping}, {@code systematic}, {@code mapping}, {@code critical},
 * {@code narrative_conceptual}, {@code targeted_related_work}, {@code
 * taxonomy_development}). {@code critical} and {@code narrative_conceptual}
 * share the critical/integrative-review family shape (ADR-081 §3). Every
 * family — including the fallback for an unmapped method key — requires
 * {@link ProtocolSectionKind#METHOD_LIMITS} and {@link
 * ProtocolSectionKind#NON_CLAIMS} (GC-RSCH-N016).
 */
public final class ProtocolMethodShape {

    /** The catalog method key for taxonomy development — the only method that may assign source roles. */
    public static final String TAXONOMY_DEVELOPMENT_METHOD_KEY = "taxonomy_development";

    private static final Set<ProtocolSectionKind> MINIMUM_HUMILITY_SECTIONS =
            Set.of(ProtocolSectionKind.METHOD_LIMITS, ProtocolSectionKind.NON_CLAIMS);

    private static final Map<String, Set<ProtocolSectionKind>> REQUIRED_SECTIONS_BY_METHOD_KEY = Map.ofEntries(
            Map.entry(
                    "scoping",
                    EnumSet.of(
                            ProtocolSectionKind.PCC_SCOPE_FRAMING,
                            ProtocolSectionKind.INFORMATION_SOURCES,
                            ProtocolSectionKind.SEARCH_STRATEGY,
                            ProtocolSectionKind.SCREENING,
                            ProtocolSectionKind.CHARTING,
                            ProtocolSectionKind.SYNTHESIS_REPORTING,
                            ProtocolSectionKind.CONSULTATION_POSTURE,
                            ProtocolSectionKind.CRITICAL_APPRAISAL_DECISION,
                            ProtocolSectionKind.PROTOCOL_REGISTRATION,
                            ProtocolSectionKind.METHOD_LIMITS,
                            ProtocolSectionKind.NON_CLAIMS)),
            Map.entry(
                    "systematic",
                    EnumSet.of(
                            ProtocolSectionKind.ELIGIBILITY_CRITERIA,
                            ProtocolSectionKind.DATABASES_SEARCH_STRINGS,
                            ProtocolSectionKind.SCREENING,
                            ProtocolSectionKind.DATA_EXTRACTION,
                            ProtocolSectionKind.RISK_OF_BIAS_POSTURE,
                            ProtocolSectionKind.SYNTHESIS_PLAN,
                            ProtocolSectionKind.REPORTING_STANDARD,
                            ProtocolSectionKind.CERTAINTY_CLAIM_LIMITS,
                            ProtocolSectionKind.METHOD_LIMITS,
                            ProtocolSectionKind.NON_CLAIMS)),
            Map.entry(
                    "mapping",
                    EnumSet.of(
                            ProtocolSectionKind.MAPPING_QUESTIONS,
                            ProtocolSectionKind.SEARCH_SCREENING_PLAN,
                            ProtocolSectionKind.CODING_MAP_SCHEMA,
                            ProtocolSectionKind.CLASSIFICATION_PROVENANCE,
                            ProtocolSectionKind.VISUALIZATION_OUTPUT,
                            ProtocolSectionKind.CLAIM_LIMITS,
                            ProtocolSectionKind.METHOD_LIMITS,
                            ProtocolSectionKind.NON_CLAIMS)),
            Map.entry(
                    "critical",
                    EnumSet.of(
                            ProtocolSectionKind.THEORETICAL_FRAME,
                            ProtocolSectionKind.SELECTION_RATIONALE,
                            ProtocolSectionKind.APPRAISAL_CRITIQUE_DIMENSIONS,
                            ProtocolSectionKind.SYNTHESIS_ARGUMENT_POSTURE,
                            ProtocolSectionKind.INCLUSION_LIMITS,
                            ProtocolSectionKind.METHOD_LIMITS,
                            ProtocolSectionKind.NON_CLAIMS)),
            Map.entry(
                    "narrative_conceptual",
                    EnumSet.of(
                            ProtocolSectionKind.THEORETICAL_FRAME,
                            ProtocolSectionKind.SELECTION_RATIONALE,
                            ProtocolSectionKind.APPRAISAL_CRITIQUE_DIMENSIONS,
                            ProtocolSectionKind.SYNTHESIS_ARGUMENT_POSTURE,
                            ProtocolSectionKind.INCLUSION_LIMITS,
                            ProtocolSectionKind.METHOD_LIMITS,
                            ProtocolSectionKind.NON_CLAIMS)),
            Map.entry(
                    "targeted_related_work",
                    EnumSet.of(
                            ProtocolSectionKind.BOUNDED_PURPOSE,
                            ProtocolSectionKind.SEED_SOURCE_STRATEGY,
                            ProtocolSectionKind.INCLUSION_RATIONALE,
                            ProtocolSectionKind.COMPARISON_DIMENSIONS,
                            ProtocolSectionKind.NON_EXHAUSTIVENESS_DISCLOSURE,
                            ProtocolSectionKind.METHOD_LIMITS,
                            ProtocolSectionKind.NON_CLAIMS)),
            Map.entry(
                    TAXONOMY_DEVELOPMENT_METHOD_KEY,
                    EnumSet.of(
                            ProtocolSectionKind.META_CHARACTERISTIC,
                            ProtocolSectionKind.UNIT_OF_ANALYSIS,
                            ProtocolSectionKind.SOURCE_ROLES,
                            ProtocolSectionKind.STARTING_CONCEPTS,
                            ProtocolSectionKind.CONSTRUCTION_PROCEDURE,
                            ProtocolSectionKind.ITERATION_LOG_PROTOCOL,
                            ProtocolSectionKind.ENDING_CONDITIONS,
                            ProtocolSectionKind.EVALUATION_PLAN,
                            ProtocolSectionKind.VALIDITY_THREATS,
                            ProtocolSectionKind.METHOD_LIMITS,
                            ProtocolSectionKind.NON_CLAIMS)));

    private ProtocolMethodShape() {
        // static helper
    }

    /**
     * The section kinds a protocol plan must include for {@code methodKey}. An
     * unmapped method key (not in the backend catalog family list above) falls
     * back to the scientific-humility minimum: {@code METHOD_LIMITS} and {@code
     * NON_CLAIMS} (GC-RSCH-N016) — every method must carry those forward even
     * when its family-specific shape is not yet modeled.
     */
    public static Set<ProtocolSectionKind> requiredSections(String methodKey) {
        if (methodKey == null) {
            return MINIMUM_HUMILITY_SECTIONS;
        }
        return REQUIRED_SECTIONS_BY_METHOD_KEY.getOrDefault(methodKey.trim(), MINIMUM_HUMILITY_SECTIONS);
    }

    /** Whether {@code methodKey} is the taxonomy-development method — the only method that may assign source roles. */
    public static boolean isTaxonomyDevelopment(String methodKey) {
        return TAXONOMY_DEVELOPMENT_METHOD_KEY.equals(methodKey == null ? null : methodKey.trim());
    }
}
