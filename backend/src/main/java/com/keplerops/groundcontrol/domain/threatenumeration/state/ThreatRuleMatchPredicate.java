package com.keplerops.groundcontrol.domain.threatenumeration.state;

/**
 * Closed predicate model for threat rule matching. Each predicate is evaluated against
 * a {@code ThreatCandidateElementView} and the byKey index of all views in the snapshot.
 * Predicates never make external calls and are total functions over their inputs.
 */
public enum ThreatRuleMatchPredicate {
    /** Always matches any element of the targeted kinds. */
    ALWAYS,
    /**
     * Matches a DATA_FLOW whose resolved source and target endpoints reside in different
     * (non-null) trust boundaries, or whose own {@code trustBoundaryKey} is non-blank.
     */
    CROSSES_TRUST_BOUNDARY,
    /** Matches a DATA_FLOW whose resolved source endpoint is an EXTERNAL_ENTITY. */
    SOURCE_IS_EXTERNAL,
    /** Matches a DATA_FLOW whose resolved target endpoint is an EXTERNAL_ENTITY. */
    TARGET_IS_EXTERNAL,
    /** Matches any element whose {@code dataClassificationKey} is non-blank. */
    HAS_DATA_CLASSIFICATION,
    /** Matches any element whose {@code trustBoundaryKey} is non-blank. */
    HAS_TRUST_BOUNDARY,
    /**
     * Matches any element whose metadata map contains the key named by
     * {@link com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatRule#metadataTagKey()}.
     */
    HAS_METADATA_TAG
}
