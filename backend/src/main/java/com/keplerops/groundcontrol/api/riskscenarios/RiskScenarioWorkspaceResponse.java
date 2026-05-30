package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskScenarioWorkspaceResult;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskRegisterStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HTTP DTO mirror of {@link RiskScenarioWorkspaceResult} for the
 * {@code GET /api/v1/risk-scenarios/workspace} endpoint (GC-Q009).
 *
 * <p>Assessment payloads (inputFactors, computedOutputs, uncertaintyMetadata, notes) are not
 * exposed — only {@code hasComputedOutputs} is surfaced (preflight error-leakage rule).
 */
public record RiskScenarioWorkspaceResponse(
        List<WorkspaceScenarioDto> scenarios, List<WorkspaceAssetDto> assets, int scenarioCount, int assetCount) {

    public static RiskScenarioWorkspaceResponse from(RiskScenarioWorkspaceResult result) {
        List<WorkspaceScenarioDto> scenarios = result.scenarios().stream()
                .map(s -> new WorkspaceScenarioDto(
                        s.id(),
                        s.uid(),
                        s.title(),
                        s.status(),
                        s.threat(),
                        s.method(),
                        s.asset(),
                        s.effect(),
                        s.timeHorizon(),
                        s.fairSentence(),
                        s.linkedAssetIds(),
                        s.linkedControls().stream()
                                .map(l -> new WorkspaceLinkDto(
                                        l.targetEntityId(), l.targetIdentifier(), l.targetTitle(), l.targetUrl()))
                                .toList(),
                        s.linkedFindings().stream()
                                .map(l -> new WorkspaceLinkDto(
                                        l.targetEntityId(), l.targetIdentifier(), l.targetTitle(), l.targetUrl()))
                                .toList(),
                        s.linkedEvidence().stream()
                                .map(l -> new WorkspaceLinkDto(
                                        l.targetEntityId(), l.targetIdentifier(), l.targetTitle(), l.targetUrl()))
                                .toList(),
                        s.linkedRequirements().stream()
                                .map(l -> new WorkspaceLinkDto(
                                        l.targetEntityId(), l.targetIdentifier(), l.targetTitle(), l.targetUrl()))
                                .toList(),
                        s.assessments().stream()
                                .map(a -> new WorkspaceAssessmentDto(
                                        a.id(),
                                        a.methodologyProfileName(),
                                        a.approvalState(),
                                        a.assessmentAt(),
                                        a.confidence(),
                                        a.reassessmentRequiredAt(),
                                        a.hasComputedOutputs()))
                                .toList(),
                        s.treatments().stream()
                                .map(t -> new WorkspaceTreatmentDto(
                                        t.id(), t.uid(), t.title(), t.strategy(), t.status(), t.owner(), t.dueDate()))
                                .toList(),
                        s.registerRecords().stream()
                                .map(r -> new WorkspaceRegisterRefDto(r.id(), r.uid(), r.title(), r.status()))
                                .toList(),
                        s.reviewIndicator()))
                .toList();
        List<WorkspaceAssetDto> assets = result.assets().stream()
                .map(a -> new WorkspaceAssetDto(a.id(), a.uid(), a.name(), a.assetType(), a.isBoundary()))
                .toList();
        return new RiskScenarioWorkspaceResponse(scenarios, assets, result.scenarioCount(), result.assetCount());
    }

    public record WorkspaceScenarioDto(
            UUID id,
            String uid,
            String title,
            RiskScenarioStatus status,
            String threat,
            String method,
            String asset,
            String effect,
            String timeHorizon,
            String fairSentence,
            List<UUID> linkedAssetIds,
            List<WorkspaceLinkDto> linkedControls,
            List<WorkspaceLinkDto> linkedFindings,
            List<WorkspaceLinkDto> linkedEvidence,
            List<WorkspaceLinkDto> linkedRequirements,
            List<WorkspaceAssessmentDto> assessments,
            List<WorkspaceTreatmentDto> treatments,
            List<WorkspaceRegisterRefDto> registerRecords,
            String reviewIndicator) {}

    public record WorkspaceAssetDto(UUID id, String uid, String name, AssetType assetType, boolean boundary) {}

    public record WorkspaceAssessmentDto(
            UUID id,
            String methodologyProfileName,
            RiskAssessmentApprovalStatus approvalState,
            Instant assessmentAt,
            String confidence,
            Instant reassessmentRequiredAt,
            boolean hasComputedOutputs) {}

    public record WorkspaceTreatmentDto(
            UUID id,
            String uid,
            String title,
            TreatmentStrategy strategy,
            TreatmentPlanStatus status,
            String owner,
            Instant dueDate) {}

    public record WorkspaceLinkDto(
            UUID targetEntityId, String targetIdentifier, String targetTitle, String targetUrl) {}

    public record WorkspaceRegisterRefDto(UUID id, String uid, String title, RiskRegisterStatus status) {}
}
