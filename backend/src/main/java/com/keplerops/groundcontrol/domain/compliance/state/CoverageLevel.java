package com.keplerops.groundcontrol.domain.compliance.state;

/**
 * Coverage qualifier per individual {@code ComplianceFrameworkMapping} row,
 * per GC-I005: how completely the mapped endpoint (requirement or control)
 * satisfies the framework element.
 *
 * <ul>
 *   <li>{@link #FULL} — the endpoint fully satisfies the framework element on
 *       its own.
 *   <li>{@link #PARTIAL} — the endpoint addresses only part of the framework
 *       element; additional mappings are expected to close the gap.
 *   <li>{@link #COMPENSATING} — the endpoint is a compensating control that
 *       satisfies the intent of the framework element through alternate means.
 * </ul>
 *
 * <p>Declaration order is part of the ADR-034 enum mirror contract.
 */
public enum CoverageLevel {
    FULL,
    PARTIAL,
    COMPENSATING
}
