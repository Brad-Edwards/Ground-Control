package com.keplerops.groundcontrol.api.riskcontrol;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record AddEvidenceRefRequest(@NotBlank String evidenceRef, String evidenceNote, UUID evidenceArtifactId) {}
