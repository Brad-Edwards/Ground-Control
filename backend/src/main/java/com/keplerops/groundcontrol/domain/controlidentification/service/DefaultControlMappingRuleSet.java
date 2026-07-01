package com.keplerops.groundcontrol.domain.controlidentification.service;

import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The built-in, deterministic control-mapping rule set (GC-GRC-008). Maps each
 * {@link ThreatRuleCategory} — and the six STRIDE categories under the STRIDE baseline — to a control
 * objective and the recognized-framework families (NIST SP 800-53 Rev. 5 / SP 800-218 SSDF) that
 * satisfy it. This is data, not logic: control selection stays auditable against recognized frameworks
 * rather than LLM-invented (GC-GRC-008 rationale, ADR-058 §3).
 *
 * <p>Distributed as code rather than a registry pack: the category→objective mapping is small,
 * universal, and framework-agnostic, and adding a new threat category or framework family means adding
 * a rule here, not rewriting the confirmation or graph code. The candidate <em>controls</em> are still
 * drawn from installed control packs and project controls at run time.
 */
public final class DefaultControlMappingRuleSet {

    public static final String RULE_SET_ID = "gc-default-control-mapping";
    public static final String VERSION = "1.0.0";

    private DefaultControlMappingRuleSet() {}

    /** The canonical built-in rule set. */
    public static ControlMappingRuleSet standard() {
        return new ControlMappingRuleSet(
                RULE_SET_ID,
                VERSION,
                List.of(
                        // --- STRIDE baseline: one objective per STRIDE category ---
                        strideRule(
                                "cmap-stride-spoofing",
                                StrideCategory.SPOOFING,
                                "identity-and-authentication",
                                "Identity and authentication",
                                Set.of("IA", "AC"),
                                "Authenticate the identity of the actor before granting access; verify credentials"
                                        + " and manage identifiers per IA-family controls."),
                        strideRule(
                                "cmap-stride-tampering",
                                StrideCategory.TAMPERING,
                                "integrity-protection",
                                "Integrity protection",
                                Set.of("SI", "SC", "CM"),
                                "Protect data and configuration integrity with validation, integrity"
                                        + " verification, and change control per SI/SC/CM-family controls."),
                        strideRule(
                                "cmap-stride-repudiation",
                                StrideCategory.REPUDIATION,
                                "audit-and-accountability",
                                "Audit and accountability",
                                Set.of("AU"),
                                "Record non-repudiable, tamper-evident audit events attributable to an actor"
                                        + " per AU-family controls."),
                        strideRule(
                                "cmap-stride-information-disclosure",
                                StrideCategory.INFORMATION_DISCLOSURE,
                                "confidentiality-protection",
                                "Confidentiality protection",
                                Set.of("SC", "AC", "MP"),
                                "Protect data confidentiality in transit and at rest and restrict access to"
                                        + " authorized actors per SC/AC/MP-family controls."),
                        strideRule(
                                "cmap-stride-denial-of-service",
                                StrideCategory.DENIAL_OF_SERVICE,
                                "availability-and-resilience",
                                "Availability and resilience",
                                Set.of("SC", "CP"),
                                "Preserve availability under load and failure with resource limits, redundancy,"
                                        + " and contingency planning per SC/CP-family controls."),
                        strideRule(
                                "cmap-stride-elevation-of-privilege",
                                StrideCategory.ELEVATION_OF_PRIVILEGE,
                                "least-privilege-authorization",
                                "Least-privilege authorization",
                                Set.of("AC", "IA"),
                                "Enforce least privilege and separation of duties on authorization decisions"
                                        + " per AC/IA-family controls."),
                        // --- Cross-cutting category rules (fire for any STRIDE within the category) ---
                        categoryRule(
                                "cmap-cat-deployment-pipeline",
                                ThreatRuleCategory.DEPLOYMENT_PIPELINE,
                                "supply-chain-and-pipeline-integrity",
                                "Supply-chain and pipeline integrity",
                                Set.of("SR", "SA", "CM"),
                                "Protect the build/deploy pipeline and its dependencies with supply-chain risk"
                                        + " management and secure development practices per SR/SA/CM and SP 800-218."),
                        categoryRule(
                                "cmap-cat-authn-authz",
                                ThreatRuleCategory.AUTHN_AUTHZ,
                                "authentication-and-access-control",
                                "Authentication and access control",
                                Set.of("IA", "AC"),
                                "Authenticate actors and enforce access-control policy on the surface per"
                                        + " IA/AC-family controls."),
                        categoryRule(
                                "cmap-cat-secret-handling",
                                ThreatRuleCategory.SECRET_HANDLING,
                                "secret-and-key-management",
                                "Secret and key management",
                                Set.of("SC", "IA"),
                                "Protect secrets and cryptographic keys throughout their lifecycle per"
                                        + " SC-12/SC-28 and IA-family controls."),
                        categoryRule(
                                "cmap-cat-untrusted-input",
                                ThreatRuleCategory.UNTRUSTED_INPUT,
                                "input-validation-and-sanitization",
                                "Input validation and sanitization",
                                Set.of("SI"),
                                "Validate and sanitize untrusted input at trust boundaries per SI-10 and related"
                                        + " SI-family controls."),
                        categoryRule(
                                "cmap-cat-data-egress",
                                ThreatRuleCategory.DATA_EGRESS,
                                "data-egress-and-boundary-protection",
                                "Data egress and boundary protection",
                                Set.of("SC", "AC"),
                                "Constrain and monitor data egress at boundary-protection points per SC-7 and"
                                        + " AC-family controls."),
                        categoryRule(
                                "cmap-cat-crypto",
                                ThreatRuleCategory.CRYPTO,
                                "cryptographic-protection",
                                "Cryptographic protection",
                                Set.of("SC"),
                                "Use vetted cryptographic protection and key establishment per SC-12/SC-13"
                                        + " controls.")));
    }

    private static ControlMappingRule strideRule(
            String ruleId,
            StrideCategory stride,
            String objectiveKey,
            String objectiveTitle,
            Set<String> selectors,
            String guidance) {
        return new ControlMappingRule(
                ruleId,
                ThreatRuleCategory.STRIDE_BASELINE,
                stride,
                objectiveKey,
                objectiveTitle,
                selectors,
                guidance,
                "STRIDE " + stride.name() + " maps to the " + objectiveTitle.toLowerCase(Locale.ROOT) + " objective.");
    }

    private static ControlMappingRule categoryRule(
            String ruleId,
            ThreatRuleCategory category,
            String objectiveKey,
            String objectiveTitle,
            Set<String> selectors,
            String guidance) {
        return new ControlMappingRule(
                ruleId,
                category,
                null,
                objectiveKey,
                objectiveTitle,
                selectors,
                guidance,
                "Threat category " + category.name() + " maps to the " + objectiveTitle.toLowerCase(Locale.ROOT)
                        + " objective.");
    }
}
