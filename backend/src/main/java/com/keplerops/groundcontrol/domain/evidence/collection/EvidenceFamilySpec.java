package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.List;

/**
 * Immutable carrier for the canonical data an evidence family contributes over the GC-S001
 * collection port: its scope type, evidence type, and the bounded summary fields a collected
 * summary may carry.
 *
 * <p>Shared by the per-domain family enums (IAM GC-S002, cloud infrastructure GC-S003, and
 * future evidence-adapter specifications) so the mechanical schema-id and capability-token
 * derivations are defined once rather than re-implemented per enum. The family-specific
 * values stay as data in each enum constant; this record only carries them.
 */
public record EvidenceFamilySpec(String scopeType, EvidenceType evidenceType, List<String> summaryFields) {

    public EvidenceFamilySpec {
        if (scopeType == null || scopeType.isBlank()) {
            throw new DomainValidationException("Evidence family scopeType must not be blank");
        }
        if (evidenceType == null) {
            throw new DomainValidationException("Evidence family evidenceType must not be null");
        }
        summaryFields = List.copyOf(summaryFields == null ? List.of() : summaryFields);
    }

    /** Canonical output-schema id; equal to {@link #scopeType()} for a 1:1 family-to-schema mapping. */
    public String schemaId() {
        return scopeType;
    }

    /** Descriptor capability token advertised by an adapter that collects this family. */
    public String capabilityToken() {
        return "family:" + scopeType;
    }
}
