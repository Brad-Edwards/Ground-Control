package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F009 / ADR-081 §3 — closed vocabulary for the semantic class of a
 * {@link ProtocolPlanSection}. The selected method profile determines which
 * subset of kinds a {@link ProtocolPlan} must include ({@link
 * com.keplerops.groundcontrol.domain.research.service.ProtocolMethodShape}).
 * {@link #METHOD_LIMITS} and {@link #NON_CLAIMS} are required by every method
 * family (GC-RSCH-N016). The set is API-visible and follows ADR-034
 * enum-contract rules; extend it deliberately rather than overloading an
 * existing value.
 *
 * <p>Scoping-review family: {@link #PCC_SCOPE_FRAMING}, {@link
 * #INFORMATION_SOURCES}, {@link #SEARCH_STRATEGY}, {@link #SCREENING}, {@link
 * #CHARTING}, {@link #SYNTHESIS_REPORTING}, {@link #CONSULTATION_POSTURE},
 * {@link #CRITICAL_APPRAISAL_DECISION}, {@link #PROTOCOL_REGISTRATION}.
 *
 * <p>Systematic-review family: {@link #ELIGIBILITY_CRITERIA}, {@link
 * #DATABASES_SEARCH_STRINGS}, {@link #SCREENING}, {@link #DATA_EXTRACTION},
 * {@link #RISK_OF_BIAS_POSTURE}, {@link #SYNTHESIS_PLAN}, {@link
 * #REPORTING_STANDARD}, {@link #CERTAINTY_CLAIM_LIMITS}.
 *
 * <p>Systematic-map family: {@link #MAPPING_QUESTIONS}, {@link
 * #SEARCH_SCREENING_PLAN}, {@link #CODING_MAP_SCHEMA}, {@link
 * #CLASSIFICATION_PROVENANCE}, {@link #VISUALIZATION_OUTPUT}, {@link
 * #CLAIM_LIMITS}.
 *
 * <p>Critical/integrative-review family: {@link #THEORETICAL_FRAME}, {@link
 * #SELECTION_RATIONALE}, {@link #APPRAISAL_CRITIQUE_DIMENSIONS}, {@link
 * #SYNTHESIS_ARGUMENT_POSTURE}, {@link #INCLUSION_LIMITS}.
 *
 * <p>Targeted-related-work family: {@link #BOUNDED_PURPOSE}, {@link
 * #SEED_SOURCE_STRATEGY}, {@link #INCLUSION_RATIONALE}, {@link
 * #COMPARISON_DIMENSIONS}, {@link #NON_EXHAUSTIVENESS_DISCLOSURE}.
 *
 * <p>Taxonomy-development family: {@link #META_CHARACTERISTIC}, {@link
 * #UNIT_OF_ANALYSIS}, {@link #SOURCE_ROLES}, {@link #STARTING_CONCEPTS}, {@link
 * #CONSTRUCTION_PROCEDURE}, {@link #ITERATION_LOG_PROTOCOL}, {@link
 * #ENDING_CONDITIONS}, {@link #EVALUATION_PLAN}, {@link #VALIDITY_THREATS}.
 */
public enum ProtocolSectionKind {
    PCC_SCOPE_FRAMING,
    INFORMATION_SOURCES,
    SEARCH_STRATEGY,
    ELIGIBILITY_CRITERIA,
    DATABASES_SEARCH_STRINGS,
    SCREENING,
    DATA_EXTRACTION,
    CHARTING,
    RISK_OF_BIAS_POSTURE,
    SYNTHESIS_PLAN,
    SYNTHESIS_REPORTING,
    REPORTING_STANDARD,
    CERTAINTY_CLAIM_LIMITS,
    CONSULTATION_POSTURE,
    CRITICAL_APPRAISAL_DECISION,
    PROTOCOL_REGISTRATION,
    MAPPING_QUESTIONS,
    SEARCH_SCREENING_PLAN,
    CODING_MAP_SCHEMA,
    CLASSIFICATION_PROVENANCE,
    VISUALIZATION_OUTPUT,
    CLAIM_LIMITS,
    THEORETICAL_FRAME,
    SELECTION_RATIONALE,
    APPRAISAL_CRITIQUE_DIMENSIONS,
    SYNTHESIS_ARGUMENT_POSTURE,
    INCLUSION_LIMITS,
    BOUNDED_PURPOSE,
    SEED_SOURCE_STRATEGY,
    INCLUSION_RATIONALE,
    COMPARISON_DIMENSIONS,
    NON_EXHAUSTIVENESS_DISCLOSURE,
    META_CHARACTERISTIC,
    UNIT_OF_ANALYSIS,
    SOURCE_ROLES,
    STARTING_CONCEPTS,
    CONSTRUCTION_PROCEDURE,
    ITERATION_LOG_PROTOCOL,
    ENDING_CONDITIONS,
    EVALUATION_PLAN,
    VALIDITY_THREATS,
    METHOD_LIMITS,
    NON_CLAIMS
}
