package com.keplerops.groundcontrol.domain.evidence.collection.iam;

import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceFamilyDescriptor;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceFamilySpec;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import java.util.List;

/**
 * The five IAM evidence families GC-S002 requires an adapter to be capable of collecting.
 *
 * <p>Each family maps onto the GC-S001 collection port as data, not as a dedicated Java
 * interface: it carries an {@link EvidenceFamilySpec} with a canonical {@code scopeType}
 * (carried in {@code EvidenceCollectionScope}) and a canonical {@code schemaId} (carried in
 * {@code EvidenceCollectionOutputSchema}). Every family is summarized as
 * {@link EvidenceType#OBSERVATION_SUMMARY} — an adapter must not present MFA, dormancy, or
 * privileged-access status as a control-effectiveness conclusion. {@link #summaryFields()}
 * names the bounded, normalized fields a collected summary carries; raw provider exports,
 * full user lists, and event bodies stay out.
 */
public enum IamEvidenceFamily implements EvidenceFamilyDescriptor {
    USER_ACCESS_REVIEW(new EvidenceFamilySpec(
            "iam-user-access-review",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of("campaignId", "reviewerId", "subjectCount", "certifiedCount", "revokedCount", "reviewedThrough"))),
    PROVISIONING_EVENT(new EvidenceFamilySpec(
            "iam-provisioning-event",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    "eventType",
                    "accountRef",
                    "actorRef",
                    "occurredThrough",
                    "provisionedCount",
                    "deprovisionedCount"))),
    MFA_ENROLLMENT(new EvidenceFamilySpec(
            "iam-mfa-enrollment",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of("subjectRef", "enrolled", "factorTypes", "enrolledCount", "notEnrolledCount"))),
    PRIVILEGED_ACCESS(new EvidenceFamilySpec(
            "iam-privileged-access",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of("subjectRef", "privilegedRoles", "grantSource", "lastUsedThrough", "privilegedAccountCount"))),
    DORMANT_ACCOUNT(new EvidenceFamilySpec(
            "iam-dormant-account",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of("accountRef", "lastActivityAt", "dormantThresholdDays", "dormantCount")));

    private final EvidenceFamilySpec spec;

    IamEvidenceFamily(EvidenceFamilySpec spec) {
        this.spec = spec;
    }

    @Override
    public EvidenceFamilySpec familySpec() {
        return spec;
    }

    /** Resolves a family by its canonical scope type, surfacing an unsupported category. */
    public static IamEvidenceFamily fromScopeType(String scopeType) {
        return EvidenceFamilyDescriptor.resolveByScopeType(
                values(), scopeType, "Unsupported IAM evidence family scope: ");
    }
}
