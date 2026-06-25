package com.keplerops.groundcontrol.domain.evidence.collection.cloud;

import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.List;

/**
 * The five cloud infrastructure evidence families GC-S003 requires an adapter to be
 * capable of collecting across AWS, Azure, and GCP.
 *
 * <p>Each family maps onto the GC-S001 collection port as data, not as a dedicated Java
 * interface: a canonical {@code scopeType} (carried in {@code EvidenceCollectionScope})
 * and a canonical {@code schemaId} (carried in {@code EvidenceCollectionOutputSchema}).
 * Every family is summarized as {@link EvidenceType#OBSERVATION_SUMMARY} — an adapter must
 * not present security-group, encryption, logging, backup, or compliance-scan status as a
 * control-effectiveness conclusion. {@link #summaryFields()} names the bounded, normalized
 * fields a collected summary carries; raw provider exports, full resource inventories,
 * policy documents, and ingress CIDRs stay out.
 */
public enum CloudEvidenceFamily {
    SECURITY_GROUP_CONFIG(
            "cloud-security-group-config",
            List.of(
                    "groupRef",
                    "ruleCount",
                    "publicIngressCount",
                    "unrestrictedIngressCount",
                    Field.EVALUATED_THROUGH)),
    ENCRYPTION_AT_REST(
            "cloud-encryption-at-rest",
            List.of(
                    Field.RESOURCE_REF,
                    "resourceType",
                    "encryptedResourceCount",
                    "unencryptedResourceCount",
                    Field.EVALUATED_THROUGH)),
    LOGGING_CONFIG(
            "cloud-logging-config",
            List.of(Field.RESOURCE_REF, "logCategory", "enabledLogCount", "disabledLogCount", Field.EVALUATED_THROUGH)),
    BACKUP_POLICY(
            "cloud-backup-policy",
            List.of(
                    Field.RESOURCE_REF,
                    "retentionDays",
                    "protectedResourceCount",
                    "unprotectedResourceCount",
                    Field.EVALUATED_THROUGH)),
    COMPLIANCE_SCAN(
            "cloud-compliance-scan",
            List.of(
                    "scannerSource",
                    "controlRef",
                    "passCount",
                    "failCount",
                    "notApplicableCount",
                    Field.EVALUATED_THROUGH));

    /** Summary-field tokens shared across families; defined once so the literals are not duplicated. */
    private static final class Field {
        private static final String RESOURCE_REF = "resourceRef";
        private static final String EVALUATED_THROUGH = "evaluatedThrough";

        private Field() {}
    }

    private final String scopeType;

    // Holds an immutable List.copyOf result; the List interface type hides that from ErrorProne.
    @SuppressWarnings("ImmutableEnumChecker")
    private final List<String> summaryFields;

    CloudEvidenceFamily(String scopeType, List<String> summaryFields) {
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
    public static CloudEvidenceFamily fromScopeType(String scopeType) {
        if (scopeType != null) {
            String normalized = scopeType.trim();
            for (CloudEvidenceFamily family : values()) {
                if (family.scopeType.equals(normalized)) {
                    return family;
                }
            }
        }
        throw new DomainValidationException("Unsupported cloud evidence family scope: " + scopeType);
    }
}
