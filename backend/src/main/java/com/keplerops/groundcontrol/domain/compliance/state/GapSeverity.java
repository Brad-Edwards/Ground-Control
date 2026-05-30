package com.keplerops.groundcontrol.domain.compliance.state;

/**
 * Severity buckets for compliance-framework gap analysis results
 * (GC-I007). Used to categorize each framework element gap so consumers can
 * query by severity (e.g. "show me all CRITICAL gaps for SOC2 readiness").
 *
 * <ul>
 *   <li>{@link #CRITICAL} — framework element has no mapping at all.
 *   <li>{@link #HIGH} — partial coverage with no compensating mappings.
 *   <li>{@link #MEDIUM} — partial coverage with at least one compensating
 *       mapping.
 *   <li>{@link #LOW} — fully covered but every mapping is compensating.
 *   <li>{@link #NONE} — fully covered with at least one non-compensating
 *       mapping (no gap).
 * </ul>
 *
 * <p>Declaration order is part of the ADR-034 enum mirror contract.
 */
public enum GapSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    NONE
}
