package com.keplerops.groundcontrol.api.decisions;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record DecisionAnalysisRecordRequest(
        @NotBlank String uid,
        @NotBlank String title,
        @NotBlank String modelName,
        String summary,
        Map<String, Object> inputs,
        Map<String, Object> simulationParameters,
        Map<String, Object> results,
        List<String> alternatives,
        String chosenAlternative,
        String rationale) {}
