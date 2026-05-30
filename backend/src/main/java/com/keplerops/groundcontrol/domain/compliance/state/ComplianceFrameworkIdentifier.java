package com.keplerops.groundcontrol.domain.compliance.state;

/**
 * Seeded compliance-framework identifiers for the {@code
 * ComplianceFrameworkMapping} aggregate (GC-I002 / GC-I005 / GC-I007 / GC-L011).
 *
 * <p>This enum is the typed first-class set; a {@code frameworkIdentifier}
 * free-form string field on the aggregate is reserved for genuine externals not
 * in this seed list. Adding a new framework here is the preferred path; the
 * external string is for ad-hoc industry / customer frameworks that do not yet
 * justify a first-class enum constant.
 *
 * <p>Declaration order is part of the ADR-034 enum mirror contract — the
 * frontend {@code ComplianceFrameworkIdentifier} union, the MCP {@code
 * COMPLIANCE_FRAMEWORK_IDENTIFIERS} constant, and the policy inventory must
 * match this order exactly.
 */
public enum ComplianceFrameworkIdentifier {
    SOC2,
    SOX,
    ISO_27001,
    NIST_CSF,
    PCI_DSS
}
