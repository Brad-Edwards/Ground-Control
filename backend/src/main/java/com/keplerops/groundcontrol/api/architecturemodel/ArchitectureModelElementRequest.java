package com.keplerops.groundcontrol.api.architecturemodel;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelProvenanceSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record ArchitectureModelElementRequest(
        @NotBlank @Size(max = 200) String stableKey,
        @NotNull ArchitectureModelElementKind elementKind,
        @NotBlank @Size(max = 200) String label,
        @Size(max = 8192) String summary,
        @Size(max = 500) String sourcePath,
        @Size(max = 120) String trustBoundaryKey,
        @Size(max = 120) String dataClassificationKey,
        @Size(max = 200) String flowSourceStableKey,
        @Size(max = 200) String flowTargetStableKey,
        ArchitectureFlowDirection flowDirection,
        @NotNull ArchitectureModelProvenanceSource provenanceSource,
        @NotBlank @Size(max = 200) String provenanceKey,
        @Size(max = 100) String adapterId,
        @Size(max = 100) String toolName,
        @Size(max = 100) String toolVersion,
        @Size(max = 200) String rulesetName,
        @Size(max = 100) String rulesetVersion,
        UUID derivationRunId,
        @Pattern(regexp = "^[0-9a-fA-F]{7,64}$") String commitSha,
        Map<String, Object> metadata) {}
