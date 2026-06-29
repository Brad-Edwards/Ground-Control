package com.keplerops.groundcontrol.api.architecturemodel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ArchitectureModelSnapshotRequest(
        @NotBlank @Size(max = 120) String modelVersion,
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{7,64}$") String commitSha,
        @NotBlank @Size(max = 40) String source,
        @Size(max = 100) String createdBy,
        @NotEmpty List<@Valid ArchitectureModelElementRequest> elements) {}
