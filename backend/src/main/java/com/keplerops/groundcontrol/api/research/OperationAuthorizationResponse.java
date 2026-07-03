package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchDataClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataForm;
import com.keplerops.groundcontrol.domain.research.model.ResearchDestinationClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchHighRiskOperationKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchOperationAuthorizationState;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunOperationAuthorization;
import java.time.Instant;
import java.util.UUID;

/** Read view of a {@link ResearchRunOperationAuthorization}. Bounded facts only. */
public record OperationAuthorizationResponse(
        UUID id,
        ResearchHighRiskOperationKind operationKind,
        String toolId,
        String sandboxProfile,
        ResearchDataClass dataClass,
        ResearchDestinationClass destinationClass,
        ResearchDataForm requestedForm,
        String targetClass,
        ResearchOperationAuthorizationState state,
        String policyBasis,
        String proposingActor,
        String decidingActor,
        String sourceActionId,
        Instant expiresAt,
        String summary,
        int attemptNo,
        Instant createdAt,
        Instant updatedAt) {

    public static OperationAuthorizationResponse from(ResearchRunOperationAuthorization a) {
        return new OperationAuthorizationResponse(
                a.getId(),
                a.getOperationKind(),
                a.getToolId(),
                a.getSandboxProfile(),
                a.getDataClass(),
                a.getDestinationClass(),
                a.getRequestedForm(),
                a.getTargetClass(),
                a.getState(),
                a.getPolicyBasis(),
                a.getProposingActor(),
                a.getDecidingActor(),
                a.getSourceActionId(),
                a.getExpiresAt(),
                a.getSummary(),
                a.getAttemptNo(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
