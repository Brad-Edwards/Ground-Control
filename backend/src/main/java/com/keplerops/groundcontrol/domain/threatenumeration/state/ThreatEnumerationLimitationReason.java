package com.keplerops.groundcontrol.domain.threatenumeration.state;

/**
 * Reason why the enumeration engine could not fully evaluate an element or produced a
 * non-fatal advisory. Limitations surface in {@code ThreatEnumerationResult.limitations()}
 * rather than silently suppressing coverage.
 */
public enum ThreatEnumerationLimitationReason {
    /** No THREAT_RULE_PACK with the requested pack id / version is registered for this project. */
    NO_RULE_PACK_RESOLVED,
    /** The project has no persisted architecture-model snapshot; enumeration returned zero candidates. */
    NO_SNAPSHOT,
    /** An element state carried an element kind not recognised by the predicate model. */
    UNKNOWN_ELEMENT_KIND,
    /** An element state had a blank or null stable key and was skipped. */
    MISSING_STABLE_KEY,
    /**
     * A DATA_FLOW predicate needed an endpoint (source or target) that was not present
     * in the evaluated snapshot.
     */
    DANGLING_FLOW_ENDPOINT
}
