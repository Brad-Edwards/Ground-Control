package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record MethodologyProfileRequest(
        @NotBlank @Size(max = 100) String profileKey,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 50) String version,
        @NotNull MethodologyFamily family,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        MethodologyProfileStatus status,
        Map<String, Object> treatmentStrategyVocabulary) {}
