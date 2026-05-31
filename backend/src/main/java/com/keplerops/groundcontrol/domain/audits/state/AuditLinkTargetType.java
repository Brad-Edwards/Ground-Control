package com.keplerops.groundcontrol.domain.audits.state;

/**
 * Target entity types for audit links (GC-U001).
 *
 * <p>{@link #COMPLIANCE_FRAMEWORK_MAPPING} is the typed successor to the
 * legacy {@link #FRAMEWORK} string path introduced in GC-I002 / GC-I005 /
 * GC-I007. New audit links to a compliance framework element must use
 * {@code COMPLIANCE_FRAMEWORK_MAPPING}; the legacy {@code FRAMEWORK} constant
 * is retained for backward compatibility with audit records authored before
 * this aggregate landed and is resolved as an {@code externalTarget}
 * (free-form identifier string).
 */
public enum AuditLinkTargetType {
    /**
     * @deprecated since GC-I002; use {@link #COMPLIANCE_FRAMEWORK_MAPPING} for
     *     new audit links. Retained for backward compatibility with persisted
     *     records that carry the legacy FRAMEWORK string path.
     */
    @Deprecated
    FRAMEWORK,
    ASSET,
    CONTROL,
    RISK_SCENARIO,
    RISK_REGISTER_RECORD,
    EVIDENCE,
    FINDING,
    EXTERNAL,
    /**
     * Typed reference to a {@code ComplianceFrameworkMapping} aggregate row.
     * Resolves as an internal target via
     * {@code ComplianceFrameworkMappingRepository}. Supersedes the legacy
     * {@link #FRAMEWORK} string path for new audit links (GC-I002).
     */
    COMPLIANCE_FRAMEWORK_MAPPING
}
