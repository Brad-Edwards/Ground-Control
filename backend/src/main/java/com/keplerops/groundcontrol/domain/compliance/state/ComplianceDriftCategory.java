package com.keplerops.groundcontrol.domain.compliance.state;

/**
 * High-level category of a compliance drift event (GC-I004).
 *
 * <p>Categories are intentionally coarse — the signal stream is durable but
 * not interpretive. Downstream consumers (posture projections, dashboards,
 * GitHub issue surfacing) decide what to do with each category. New
 * categories require a new enum value here AND the mirrored MCP / frontend
 * arrays (ADR-034 enum policy).
 */
public enum ComplianceDriftCategory {
    /** A control's status or effectiveness moved. */
    CONTROL_STATE_CHANGED,
    /** An evidence artifact's {@code expiresAt} elapsed. */
    EVIDENCE_EXPIRED,
    /** A code-change touched an artifact the compliance posture depends on. */
    CODE_CHANGE_IMPACT,
    /** A previously-published drift event was observed to have resolved. */
    RESOLUTION
}
