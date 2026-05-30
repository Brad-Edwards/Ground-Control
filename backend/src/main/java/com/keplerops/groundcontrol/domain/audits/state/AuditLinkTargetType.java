package com.keplerops.groundcontrol.domain.audits.state;

/**
 * Target entity types for audit links (GC-U001).
 *
 * <p>{@link #FRAMEWORK} is kept for backward compatibility with audit records
 * authored before GC-I002 / GC-I005 / GC-I007 promoted compliance framework
 * mappings into the first-class
 * {@code com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping}
 * aggregate. New audit links to a framework element should be authored against
 * the aggregate (via a future {@code COMPLIANCE_FRAMEWORK_MAPPING} target type)
 * once the next ADR carves out the deprecation. Until then the FRAMEWORK string
 * path remains a valid {@code externalTarget} on the audit-link resolver.
 */
public enum AuditLinkTargetType {
    /** @deprecated since GC-I002; prefer the ComplianceFrameworkMapping aggregate. */
    @Deprecated
    FRAMEWORK,
    ASSET,
    CONTROL,
    RISK_SCENARIO,
    RISK_REGISTER_RECORD,
    EVIDENCE,
    FINDING,
    EXTERNAL
}
