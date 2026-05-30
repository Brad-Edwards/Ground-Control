package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MonitoredRiskFactor;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ReassessmentTrigger;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TreatmentPlanResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        String uid,
        String title,
        UUID riskRegisterRecordId,
        String riskRegisterRecordUid,
        UUID riskScenarioId,
        String riskScenarioUid,
        TreatmentStrategy strategy,
        String owner,
        String rationale,
        Instant dueDate,
        TreatmentPlanStatus status,
        List<ActionItem> actionItems,
        List<ReassessmentTrigger> reassessmentTriggers,
        UUID methodologyProfileId,
        String methodologyStrategyKey,
        UUID riskAssessmentResultId,
        List<MonitoredRiskFactor> monitoredRiskFactors,
        String updateCadence,
        Instant createdAt,
        Instant updatedAt) {

    public static TreatmentPlanResponse from(TreatmentPlan plan) {
        return new TreatmentPlanResponse(
                plan.getId(),
                GraphIds.nodeId(GraphEntityType.TREATMENT_PLAN, plan.getId()),
                plan.getProject().getIdentifier(),
                plan.getUid(),
                plan.getTitle(),
                plan.getRiskRegisterRecord().getId(),
                plan.getRiskRegisterRecord().getUid(),
                plan.getRiskScenario() != null ? plan.getRiskScenario().getId() : null,
                plan.getRiskScenario() != null ? plan.getRiskScenario().getUid() : null,
                plan.getStrategy(),
                plan.getOwner(),
                plan.getRationale(),
                plan.getDueDate(),
                plan.getStatus(),
                plan.getActionItems(),
                plan.getReassessmentTriggers(),
                plan.getMethodologyProfile() != null
                        ? plan.getMethodologyProfile().getId()
                        : null,
                plan.getMethodologyStrategyKey(),
                plan.getRiskAssessmentResult() != null
                        ? plan.getRiskAssessmentResult().getId()
                        : null,
                plan.getMonitoredRiskFactors(),
                plan.getUpdateCadence(),
                plan.getCreatedAt(),
                plan.getUpdatedAt());
    }
}
