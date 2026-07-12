package com.keplerops.groundcontrol.api.packregistry;

import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryImportFormat;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryImportOptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record PackRegistryImportRequest(
        PackRegistryImportFormat format,
        @Size(max = 200) String packId,
        @Size(max = 50) String version,
        @Size(max = 200) String publisher,
        String description,
        @Size(max = 2000) String sourceUrl,
        @Size(max = 128) String checksum,
        Map<String, Object> signatureInfo,
        Map<String, Object> compatibility,
        @Valid List<PackDependencyRequest> dependencies,
        Map<String, Object> provenance,
        Map<String, Object> registryMetadata) {

    public PackRegistryImportOptions toOptions() {
        return new PackRegistryImportOptions(
                format,
                packId,
                version,
                publisher,
                description,
                sourceUrl,
                checksum,
                signatureInfo,
                compatibility,
                PackDependencyRequest.toDomainList(dependencies),
                provenance,
                registryMetadata);
    }
}
