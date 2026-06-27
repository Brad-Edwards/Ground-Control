package com.keplerops.groundcontrol.domain.evidence.collection.cloud;

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
 * Normative specification for cloud infrastructure evidence collection adapters (GC-S003,
 * AWS / Azure / GCP).
 *
 * <p>This is the single reference a conforming adapter — and its contract test — anchors
 * on. It defines, as data over the GC-S001 {@code EvidenceCollectionAdapter} port: the
 * supported {@link CloudEvidenceProvider providers}, the {@link CloudEvidenceFamily
 * families}, the versioned output schema per family, and the {@link PluginDescriptor}
 * capability set an adapter advertises so {@code EvidenceCollectionAdapterRegistry} can
 * discover it. It introduces no new adapter interface, persistence, transport, or
 * credential store; concrete provider collectors remain out of scope.
 */
public final class CloudEvidenceSpecification {

    /** Schema version for all GC-S003 cloud infrastructure output schemas. */
    public static final String SCHEMA_VERSION = "1.0.0";

    /** Capability marker every cloud infrastructure evidence adapter advertises. */
    public static final String CAPABILITY_CLOUD_INFRASTRUCTURE = "evidence:cloud-infrastructure";

    private CloudEvidenceSpecification() {}

    public static Set<CloudEvidenceProvider> supportedProviders() {
        return Set.of(CloudEvidenceProvider.values());
    }

    public static Set<CloudEvidenceFamily> supportedFamilies() {
        return Set.of(CloudEvidenceFamily.values());
    }

    /** Builds the canonical, versioned output schema for an evidence family. */
    public static EvidenceCollectionOutputSchema outputSchema(CloudEvidenceFamily family) {
        if (family == null) {
            throw new DomainValidationException("Cloud evidence family must not be null");
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
        for (CloudEvidenceFamily family : CloudEvidenceFamily.values()) {
            schemas.add(outputSchema(family));
        }
        return List.copyOf(schemas);
    }

    /**
     * Descriptor capability set a conforming adapter advertises for a provider: the cloud
     * infrastructure marker, the provider key token, and every collectable family token.
     */
    public static Set<String> capabilitiesFor(CloudEvidenceProvider provider) {
        if (provider == null) {
            throw new DomainValidationException("Cloud evidence provider must not be null");
        }
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add(CAPABILITY_CLOUD_INFRASTRUCTURE);
        capabilities.add(provider.capabilityToken());
        for (CloudEvidenceFamily family : CloudEvidenceFamily.values()) {
            capabilities.add(family.capabilityToken());
        }
        return Set.copyOf(capabilities);
    }

    /**
     * Validates that a descriptor is a conformant cloud infrastructure evidence collector:
     * an {@code EVIDENCE_COLLECTOR} plugin advertising the
     * {@link #CAPABILITY_CLOUD_INFRASTRUCTURE} marker.
     *
     * @throws DomainValidationException when the descriptor is not a conformant cloud
     *     infrastructure collector.
     */
    public static void requireConformant(PluginDescriptor descriptor) {
        if (descriptor == null) {
            throw new DomainValidationException("Cloud evidence adapter descriptor must not be null");
        }
        if (descriptor.type() != PluginType.EVIDENCE_COLLECTOR) {
            throw new DomainValidationException(
                    "Cloud evidence adapter must be an EVIDENCE_COLLECTOR plugin, was " + descriptor.type());
        }
        Set<String> capabilities = descriptor.capabilities();
        if (capabilities == null || !capabilities.contains(CAPABILITY_CLOUD_INFRASTRUCTURE)) {
            throw new DomainValidationException("Cloud evidence adapter descriptor must advertise the "
                    + CAPABILITY_CLOUD_INFRASTRUCTURE + " capability");
        }
    }
}
