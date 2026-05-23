package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MethodologyProfileResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        String profileKey,
        String name,
        String version,
        MethodologyFamily family,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        MethodologyProfileStatus status,
        Map<String, Object> treatmentStrategyVocabulary,
        Instant createdAt,
        Instant updatedAt) {

    public static MethodologyProfileResponse from(MethodologyProfile profile) {
        return new MethodologyProfileResponse(
                profile.getId(),
                GraphIds.nodeId(GraphEntityType.METHODOLOGY_PROFILE, profile.getId()),
                profile.getProject().getIdentifier(),
                profile.getProfileKey(),
                profile.getName(),
                profile.getVersion(),
                profile.getFamily(),
                profile.getDescription(),
                profile.getInputSchema(),
                profile.getOutputSchema(),
                profile.getStatus(),
                profile.getTreatmentStrategyVocabulary(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
