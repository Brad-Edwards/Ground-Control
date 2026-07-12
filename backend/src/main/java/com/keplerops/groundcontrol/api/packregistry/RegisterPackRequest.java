package com.keplerops.groundcontrol.api.packregistry;

import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record RegisterPackRequest(
        @NotBlank @Size(max = 200) String packId,
        @NotNull PackType packType,
        @NotBlank @Size(max = 50) String version,
        @Size(max = 200) String publisher,
        String description,
        @Size(max = 2000) String sourceUrl,
        @Size(max = 128) String checksum,
        Map<String, Object> signatureInfo,
        Map<String, Object> compatibility,
        @Valid List<PackDependencyRequest> dependencies,
        Map<String, Object> provenance,
        Map<String, Object> registryMetadata) {}
