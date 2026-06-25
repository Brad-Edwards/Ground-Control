package com.keplerops.groundcontrol.domain.evidence.collection.iam;

import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionOutputSchema;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.plugins.service.PluginDescriptor;
import com.keplerops.groundcontrol.domain.plugins.state.PluginType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normative specification for IAM evidence collection adapters (GC-S002, Okta / Azure AD
 * / AWS IAM).
 *
 * <p>This is the single reference a conforming adapter — and its contract test — anchors
 * on. It defines, as data over the GC-S001 {@code EvidenceCollectionAdapter} port: the
 * supported {@link IamEvidenceProvider providers}, the {@link IamEvidenceFamily families},
 * the versioned output schema per family, and the {@link PluginDescriptor} capability set
 * an adapter advertises so {@code EvidenceCollectionAdapterRegistry} can discover it. It
 * introduces no new adapter interface, persistence, transport, or credential store;
 * concrete provider collectors remain out of scope.
 */
public final class IamEvidenceSpecification {

    /** Schema version for all GC-S002 IAM output schemas. */
    public static final String SCHEMA_VERSION = "1.0.0";

    /** Capability marker every IAM evidence adapter advertises. */
    public static final String CAPABILITY_IAM = "evidence:iam";

    private IamEvidenceSpecification() {}

    public static Set<IamEvidenceProvider> supportedProviders() {
        return Set.of(IamEvidenceProvider.values());
    }

    public static Set<IamEvidenceFamily> supportedFamilies() {
        return Set.of(IamEvidenceFamily.values());
    }

    /** Builds the canonical, versioned output schema for an evidence family. */
    public static EvidenceCollectionOutputSchema outputSchema(IamEvidenceFamily family) {
        if (family == null) {
            throw new DomainValidationException("IAM evidence family must not be null");
        }
        Map<String, Object> payloadShape = new LinkedHashMap<>();
        for (String field : family.summaryFields()) {
            payloadShape.put(field, "summary");
        }
        return new EvidenceCollectionOutputSchema(
                family.schemaId(), SCHEMA_VERSION, family.evidenceType(), payloadShape);
    }

    /** All five canonical output schemas, in family declaration order. */
    public static List<EvidenceCollectionOutputSchema> outputSchemas() {
        List<EvidenceCollectionOutputSchema> schemas = new ArrayList<>();
        for (IamEvidenceFamily family : IamEvidenceFamily.values()) {
            schemas.add(outputSchema(family));
        }
        return List.copyOf(schemas);
    }

    /**
     * Descriptor capability set a conforming adapter advertises for a provider: the IAM
     * marker, the provider key token, and every collectable family token.
     */
    public static Set<String> capabilitiesFor(IamEvidenceProvider provider) {
        if (provider == null) {
            throw new DomainValidationException("IAM evidence provider must not be null");
        }
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add(CAPABILITY_IAM);
        capabilities.add(provider.capabilityToken());
        for (IamEvidenceFamily family : IamEvidenceFamily.values()) {
            capabilities.add(family.capabilityToken());
        }
        return Set.copyOf(capabilities);
    }

    /**
     * Validates that a descriptor is a conformant IAM evidence collector: an
     * {@code EVIDENCE_COLLECTOR} plugin advertising the {@link #CAPABILITY_IAM} marker.
     *
     * @throws DomainValidationException when the descriptor is not a conformant IAM collector.
     */
    public static void requireConformant(PluginDescriptor descriptor) {
        if (descriptor == null) {
            throw new DomainValidationException("IAM evidence adapter descriptor must not be null");
        }
        if (descriptor.type() != PluginType.EVIDENCE_COLLECTOR) {
            throw new DomainValidationException(
                    "IAM evidence adapter must be an EVIDENCE_COLLECTOR plugin, was " + descriptor.type());
        }
        Set<String> capabilities = descriptor.capabilities();
        if (capabilities == null || !capabilities.contains(CAPABILITY_IAM)) {
            throw new DomainValidationException(
                    "IAM evidence adapter descriptor must advertise the " + CAPABILITY_IAM + " capability");
        }
    }
}
