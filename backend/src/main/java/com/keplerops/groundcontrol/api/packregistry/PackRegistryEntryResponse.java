package com.keplerops.groundcontrol.api.packregistry;

import com.keplerops.groundcontrol.domain.packregistry.model.PackDependency;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.state.CatalogStatus;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PackRegistryEntryResponse(
        UUID id,
        String projectIdentifier,
        String packId,
        PackType packType,
        String publisher,
        String version,
        String description,
        String sourceUrl,
        String checksum,
        Map<String, Object> signatureInfo,
        Map<String, Object> compatibility,
        List<PackDependency> dependencies,
        Map<String, Object> provenance,
        Map<String, Object> registryMetadata,
        CatalogStatus catalogStatus,
        Instant registeredAt,
        Instant createdAt,
        Instant updatedAt) {

    public static PackRegistryEntryResponse from(PackRegistryEntry entry) {
        return new PackRegistryEntryResponse(
                entry.getId(),
                entry.getProject().getIdentifier(),
                entry.getPackId(),
                entry.getPackType(),
                entry.getPublisher(),
                entry.getVersion(),
                entry.getDescription(),
                entry.getSourceUrl(),
                entry.getChecksum(),
                entry.getSignatureInfo(),
                entry.getCompatibility(),
                entry.getDependencies(),
                entry.getProvenance(),
                entry.getRegistryMetadata(),
                entry.getCatalogStatus(),
                entry.getRegisteredAt(),
                entry.getCreatedAt(),
                entry.getUpdatedAt());
    }
}
