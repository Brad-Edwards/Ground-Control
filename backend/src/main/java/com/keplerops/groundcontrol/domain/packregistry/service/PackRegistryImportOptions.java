package com.keplerops.groundcontrol.domain.packregistry.service;

import com.keplerops.groundcontrol.domain.packregistry.model.PackDependency;
import java.util.List;
import java.util.Map;

public record PackRegistryImportOptions(
        PackRegistryImportFormat format,
        String packId,
        String version,
        String publisher,
        String description,
        String sourceUrl,
        String checksum,
        Map<String, Object> signatureInfo,
        Map<String, Object> compatibility,
        List<PackDependency> dependencies,
        Map<String, Object> provenance,
        Map<String, Object> registryMetadata) {

    public PackRegistryImportOptions {
        format = format != null ? format : PackRegistryImportFormat.AUTO;
    }
}
