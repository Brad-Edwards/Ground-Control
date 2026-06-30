package com.keplerops.groundcontrol.api.packregistry;

import com.keplerops.groundcontrol.api.controlpacks.ControlPackEntryDefinitionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record UpdatePackRegistryEntryRequest(
        @Size(max = 200) String publisher,
        String description,
        @Size(max = 2000) String sourceUrl,
        @Size(max = 128) String checksum,
        Map<String, Object> signatureInfo,
        Map<String, Object> compatibility,
        @Valid List<PackDependencyRequest> dependencies,
        @Valid List<ControlPackEntryDefinitionRequest> controlPackEntries,
        @Valid List<ThreatRuleEntryDefinitionRequest> threatRuleEntries,
        Map<String, Object> provenance,
        Map<String, Object> registryMetadata) {}
