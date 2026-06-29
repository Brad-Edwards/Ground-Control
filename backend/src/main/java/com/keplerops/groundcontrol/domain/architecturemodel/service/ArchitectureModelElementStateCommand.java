package com.keplerops.groundcontrol.domain.architecturemodel.service;

import java.util.Map;
import java.util.UUID;

public record ArchitectureModelElementStateCommand(
        String stableKey,
        ArchitectureModelElementKind elementKind,
        String label,
        String summary,
        String sourcePath,
        String trustBoundaryKey,
        String dataClassificationKey,
        String flowSourceStableKey,
        String flowTargetStableKey,
        ArchitectureFlowDirection flowDirection,
        ArchitectureModelProvenanceSource provenanceSource,
        String provenanceKey,
        String adapterId,
        String toolName,
        String toolVersion,
        String rulesetName,
        String rulesetVersion,
        UUID derivationRunId,
        String commitSha,
        Map<String, Object> metadata) {

    public ArchitectureModelElementStateCommand {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
