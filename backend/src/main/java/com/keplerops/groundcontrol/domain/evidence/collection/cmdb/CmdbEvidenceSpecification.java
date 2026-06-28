package com.keplerops.groundcontrol.domain.evidence.collection.cmdb;

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
 * Normative specification for CMDB and asset-management evidence collection adapters
 * (GC-S004, ServiceNow / Snipe-IT / Jamf).
 *
 * <p>This is the single reference a conforming adapter — and its contract test — anchors
 * on. It defines, as data over the GC-S001 {@code EvidenceCollectionAdapter} port: the
 * supported {@link CmdbEvidenceProvider providers}, the {@link CmdbEvidenceFamily families},
 * the versioned output schema per family, and the {@link PluginDescriptor} capability set an
 * adapter advertises so {@code EvidenceCollectionAdapterRegistry} can discover it. It
 * introduces no new adapter interface, asset-inventory table, persistence, transport, or
 * credential store; concrete provider collectors remain out of scope.
 */
public final class CmdbEvidenceSpecification {

    /** Schema version for all GC-S004 CMDB and asset output schemas. */
    public static final String SCHEMA_VERSION = "1.0.0";

    /** Capability marker every CMDB and asset evidence adapter advertises. */
    public static final String CAPABILITY_CMDB = "evidence:cmdb";

    private CmdbEvidenceSpecification() {}

    public static Set<CmdbEvidenceProvider> supportedProviders() {
        return Set.of(CmdbEvidenceProvider.values());
    }

    public static Set<CmdbEvidenceFamily> supportedFamilies() {
        return Set.of(CmdbEvidenceFamily.values());
    }

    /** Builds the canonical, versioned output schema for an evidence family. */
    public static EvidenceCollectionOutputSchema outputSchema(CmdbEvidenceFamily family) {
        if (family == null) {
            throw new DomainValidationException("CMDB evidence family must not be null");
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
        for (CmdbEvidenceFamily family : CmdbEvidenceFamily.values()) {
            schemas.add(outputSchema(family));
        }
        return List.copyOf(schemas);
    }

    /**
     * Descriptor capability set a conforming adapter advertises for a provider: the CMDB
     * marker, the provider key token, and every collectable family token.
     */
    public static Set<String> capabilitiesFor(CmdbEvidenceProvider provider) {
        if (provider == null) {
            throw new DomainValidationException("CMDB evidence provider must not be null");
        }
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add(CAPABILITY_CMDB);
        capabilities.add(provider.capabilityToken());
        for (CmdbEvidenceFamily family : CmdbEvidenceFamily.values()) {
            capabilities.add(family.capabilityToken());
        }
        return Set.copyOf(capabilities);
    }

    /**
     * Validates that a descriptor is a conformant CMDB evidence collector: an
     * {@code EVIDENCE_COLLECTOR} plugin advertising the {@link #CAPABILITY_CMDB} marker.
     *
     * @throws DomainValidationException when the descriptor is not a conformant CMDB
     *     collector.
     */
    public static void requireConformant(PluginDescriptor descriptor) {
        if (descriptor == null) {
            throw new DomainValidationException("CMDB evidence adapter descriptor must not be null");
        }
        if (descriptor.type() != PluginType.EVIDENCE_COLLECTOR) {
            throw new DomainValidationException(
                    "CMDB evidence adapter must be an EVIDENCE_COLLECTOR plugin, was " + descriptor.type());
        }
        Set<String> capabilities = descriptor.capabilities();
        if (capabilities == null || !capabilities.contains(CAPABILITY_CMDB)) {
            throw new DomainValidationException(
                    "CMDB evidence adapter descriptor must advertise the " + CAPABILITY_CMDB + " capability");
        }
    }
}
