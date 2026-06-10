package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RiskAssessmentResultResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        UUID riskScenarioId,
        String riskScenarioUid,
        UUID riskRegisterRecordId,
        String riskRegisterRecordUid,
        UUID methodologyProfileId,
        String methodologyProfileKey,
        String analystIdentity,
        String assumptions,
        Map<String, Object> inputFactors,
        Instant observationDate,
        Instant assessmentAt,
        String timeHorizon,
        String confidence,
        Map<String, Object> uncertaintyMetadata,
        Map<String, Object> computedOutputs,
        RiskAssessmentApprovalStatus approvalState,
        List<String> evidenceRefs,
        String notes,
        List<UUID> observationIds,
        Instant reassessmentRequiredAt,
        Instant createdAt,
        Instant updatedAt) {

    public static RiskAssessmentResultResponse from(RiskAssessmentResult result) {
        return new RiskAssessmentResultResponse(
                result.getId(),
                GraphIds.nodeId(GraphEntityType.RISK_ASSESSMENT_RESULT, result.getId()),
                result.getProject().getIdentifier(),
                result.getRiskScenario().getId(),
                result.getRiskScenario().getUid(),
                result.getRiskRegisterRecord() != null
                        ? result.getRiskRegisterRecord().getId()
                        : null,
                result.getRiskRegisterRecord() != null
                        ? result.getRiskRegisterRecord().getUid()
                        : null,
                result.getMethodologyProfile().getId(),
                result.getMethodologyProfile().getProfileKey(),
                result.getAnalystIdentity(),
                result.getAssumptions(),
                result.getInputFactors(),
                result.getObservationDate(),
                result.getAssessmentAt(),
                result.getTimeHorizon(),
                result.getConfidence(),
                result.getUncertaintyMetadata(),
                result.getComputedOutputs(),
                result.getApprovalState(),
                result.getEvidenceRefs(),
                result.getNotes(),
                result.getObservations().stream()
                        .map(observation -> observation.getId())
                        .toList(),
                result.getReassessmentRequiredAt(),
                result.getCreatedAt(),
                result.getUpdatedAt());
    }
}
