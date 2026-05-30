package com.keplerops.groundcontrol.domain.compliance.state;

/**
 * Severity band on a compliance drift event (GC-I004).
 *
 * <p>The bands are stable across categories: a CONTROL_STATE_CHANGED event
 * and an EVIDENCE_EXPIRED event can both be {@code WARN}, and downstream
 * consumers route on severity uniformly. Severity is set by the publishing
 * service from the event payload; it is not user-editable.
 */
public enum ComplianceDriftSeverity {
    INFO,
    WARN,
    SEVERE
}
