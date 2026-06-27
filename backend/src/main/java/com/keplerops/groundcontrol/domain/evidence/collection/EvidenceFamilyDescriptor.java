package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.List;

/**
 * Common read contract for the per-domain evidence-family enums (IAM GC-S002, cloud
 * infrastructure GC-S003, and future evidence-adapter specifications).
 *
 * <p>Each family is backed by an {@link EvidenceFamilySpec}; the accessors default to that
 * spec so the trivial mechanics are not re-implemented per enum. Implementers add only the
 * family-specific data (as enum constants) and a scope-type resolver, which can delegate to
 * {@link #resolveByScopeType(EvidenceFamilyDescriptor[], String, String)}.
 */
public interface EvidenceFamilyDescriptor {

    EvidenceFamilySpec familySpec();

    default String scopeType() {
        return familySpec().scopeType();
    }

    default String schemaId() {
        return familySpec().schemaId();
    }

    default EvidenceType evidenceType() {
        return familySpec().evidenceType();
    }

    default List<String> summaryFields() {
        return familySpec().summaryFields();
    }

    default String capabilityToken() {
        return familySpec().capabilityToken();
    }

    /**
     * Resolves a family by its canonical scope type over the supplied values.
     *
     * @throws DomainValidationException when no family declares the scope type, so an
     *     unsupported category is surfaced rather than silently producing an empty report.
     */
    static <F extends EvidenceFamilyDescriptor> F resolveByScopeType(
            F[] values, String scopeType, String unsupportedMessagePrefix) {
        if (scopeType != null) {
            String normalized = scopeType.trim();
            for (F family : values) {
                if (family.scopeType().equals(normalized)) {
                    return family;
                }
            }
        }
        throw new DomainValidationException(unsupportedMessagePrefix + scopeType);
    }
}
