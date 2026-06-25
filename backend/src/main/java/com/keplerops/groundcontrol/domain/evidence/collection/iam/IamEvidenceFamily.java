package com.keplerops.groundcontrol.domain.evidence.collection.iam;

import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.List;

/**
 * The five IAM evidence families GC-S002 requires an adapter to be capable of collecting.
 *
 * <p>Each family maps onto the GC-S001 collection port as data, not as a dedicated Java
 * interface: a canonical {@code scopeType} (carried in {@code EvidenceCollectionScope})
 * and a canonical {@code schemaId} (carried in {@code EvidenceCollectionOutputSchema}).
 * Every family is summarized as {@link EvidenceType#OBSERVATION_SUMMARY} — an adapter
 * must not present MFA, dormancy, or privileged-access status as a control-effectiveness
 * conclusion. {@link #summaryFields()} names the bounded, normalized fields a collected
 * summary carries; raw provider exports, full user lists, and event bodies stay out.
 */
public enum IamEvidenceFamily {
    USER_ACCESS_REVIEW(
            "iam-user-access-review",
            List.of("campaignId", "reviewerId", "subjectCount", "certifiedCount", "revokedCount", "reviewedThrough")),
    PROVISIONING_EVENT(
            "iam-provisioning-event",
            List.of(
                    "eventType",
                    "accountRef",
                    "actorRef",
                    "occurredThrough",
                    "provisionedCount",
                    "deprovisionedCount")),
    MFA_ENROLLMENT(
            "iam-mfa-enrollment",
            List.of("subjectRef", "enrolled", "factorTypes", "enrolledCount", "notEnrolledCount")),
    PRIVILEGED_ACCESS(
            "iam-privileged-access",
            List.of("subjectRef", "privilegedRoles", "grantSource", "lastUsedThrough", "privilegedAccountCount")),
    DORMANT_ACCOUNT(
            "iam-dormant-account", List.of("accountRef", "lastActivityAt", "dormantThresholdDays", "dormantCount"));

    private final String scopeType;

    // Holds an immutable List.copyOf result; the List interface type hides that from ErrorProne.
    @SuppressWarnings("ImmutableEnumChecker")
    private final List<String> summaryFields;

    IamEvidenceFamily(String scopeType, List<String> summaryFields) {
        this.scopeType = scopeType;
        this.summaryFields = List.copyOf(summaryFields);
    }

    public String scopeType() {
        return scopeType;
    }

    /** Canonical output-schema id; equal to {@link #scopeType()} for a 1:1 family-to-schema mapping. */
    public String schemaId() {
        return scopeType;
    }

    public EvidenceType evidenceType() {
        return EvidenceType.OBSERVATION_SUMMARY;
    }

    public List<String> summaryFields() {
        return List.copyOf(summaryFields);
    }

    /** Descriptor capability token advertised by an adapter that collects this family. */
    public String capabilityToken() {
        return "family:" + scopeType;
    }

    /**
     * Resolves a family by its canonical scope type.
     *
     * @throws DomainValidationException when no family declares the scope type, so an
     *     unsupported category is surfaced rather than silently producing an empty report.
     */
    public static IamEvidenceFamily fromScopeType(String scopeType) {
        if (scopeType != null) {
            String normalized = scopeType.trim();
            for (IamEvidenceFamily family : values()) {
                if (family.scopeType.equals(normalized)) {
                    return family;
                }
            }
        }
        throw new DomainValidationException("Unsupported IAM evidence family scope: " + scopeType);
    }
}
