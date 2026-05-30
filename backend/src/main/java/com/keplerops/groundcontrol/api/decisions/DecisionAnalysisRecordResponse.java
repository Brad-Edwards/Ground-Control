package com.keplerops.groundcontrol.api.decisions;

import com.keplerops.groundcontrol.domain.decisions.model.DecisionAnalysisRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DecisionAnalysisRecordResponse(
        UUID id,
        String projectIdentifier,
        String uid,
        String title,
        String modelName,
        String summary,
        Map<String, Object> inputs,
        Map<String, Object> simulationParameters,
        Map<String, Object> results,
        List<String> alternatives,
        String chosenAlternative,
        String rationale,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static DecisionAnalysisRecordResponse from(DecisionAnalysisRecord r) {
        return new DecisionAnalysisRecordResponse(
                r.getId(),
                r.getProject().getIdentifier(),
                r.getUid(),
                r.getTitle(),
                r.getModelName(),
                r.getSummary(),
                r.getInputs(),
                r.getSimulationParameters(),
                r.getResults(),
                r.getAlternatives(),
                r.getChosenAlternative(),
                r.getRationale(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
