package com.keplerops.groundcontrol.domain.decisions.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateDecisionAnalysisRecordCommand(
        UUID projectId,
        String uid,
        String title,
        String modelName,
        String summary,
        Map<String, Object> inputs,
        Map<String, Object> simulationParameters,
        Map<String, Object> results,
        List<String> alternatives,
        String chosenAlternative,
        String rationale) {}
