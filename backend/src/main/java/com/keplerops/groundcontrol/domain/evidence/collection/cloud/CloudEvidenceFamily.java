package com.keplerops.groundcontrol.domain.evidence.collection.cloud;

import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceFamilyDescriptor;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceFamilySpec;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import java.util.List;

/**
 * The five cloud infrastructure evidence families GC-S003 requires an adapter to be
 * capable of collecting across AWS, Azure, and GCP.
 *
 * <p>Each family maps onto the GC-S001 collection port as data, not as a dedicated Java
 * interface: it carries an {@link EvidenceFamilySpec} with a canonical {@code scopeType}
 * (carried in {@code EvidenceCollectionScope}) and a canonical {@code schemaId} (carried in
 * {@code EvidenceCollectionOutputSchema}). Every family is summarized as
 * {@link EvidenceType#OBSERVATION_SUMMARY} — an adapter must not present security-group,
 * encryption, logging, backup, or compliance-scan status as a control-effectiveness
 * conclusion. {@link #summaryFields()} names the bounded, normalized fields a collected
 * summary carries; raw provider exports, full resource inventories, policy documents, and
 * ingress CIDRs stay out.
 */
public enum CloudEvidenceFamily implements EvidenceFamilyDescriptor {
    SECURITY_GROUP_CONFIG(new EvidenceFamilySpec(
            "cloud-security-group-config",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    "groupRef",
                    "ruleCount",
                    "publicIngressCount",
                    "unrestrictedIngressCount",
                    Field.EVALUATED_THROUGH))),
    ENCRYPTION_AT_REST(new EvidenceFamilySpec(
            "cloud-encryption-at-rest",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    Field.RESOURCE_REF,
                    "resourceType",
                    "encryptedResourceCount",
                    "unencryptedResourceCount",
                    Field.EVALUATED_THROUGH))),
    LOGGING_CONFIG(new EvidenceFamilySpec(
            "cloud-logging-config",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    Field.RESOURCE_REF,
                    "logCategory",
                    "enabledLogCount",
                    "disabledLogCount",
                    Field.EVALUATED_THROUGH))),
    BACKUP_POLICY(new EvidenceFamilySpec(
            "cloud-backup-policy",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    Field.RESOURCE_REF,
                    "retentionDays",
                    "protectedResourceCount",
                    "unprotectedResourceCount",
                    Field.EVALUATED_THROUGH))),
    COMPLIANCE_SCAN(new EvidenceFamilySpec(
            "cloud-compliance-scan",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    "scannerSource",
                    "controlRef",
                    "passCount",
                    "failCount",
                    "notApplicableCount",
                    Field.EVALUATED_THROUGH)));

    /** Summary-field tokens shared across families; defined once so the literals are not duplicated. */
    private static final class Field {
        private static final String RESOURCE_REF = "resourceRef";
        private static final String EVALUATED_THROUGH = "evaluatedThrough";

        private Field() {}
    }

    private final EvidenceFamilySpec spec;

    CloudEvidenceFamily(EvidenceFamilySpec spec) {
        this.spec = spec;
    }

    @Override
    public EvidenceFamilySpec familySpec() {
        return spec;
    }

    /** Resolves a family by its canonical scope type, surfacing an unsupported category. */
    public static CloudEvidenceFamily fromScopeType(String scopeType) {
        return EvidenceFamilyDescriptor.resolveByScopeType(
                values(), scopeType, "Unsupported cloud evidence family scope: ");
    }
}
