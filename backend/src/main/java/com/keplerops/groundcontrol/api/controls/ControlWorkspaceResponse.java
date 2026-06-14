package com.keplerops.groundcontrol.api.controls;

import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ControlWorkspaceResponse(List<WorkspaceControlDto> controls, int controlCount) {

    public static ControlWorkspaceResponse from(ControlWorkspaceResult result) {
        return new ControlWorkspaceResponse(
                result.controls().stream()
                        .map(ControlWorkspaceResponse::toControl)
                        .toList(),
                result.controlCount());
    }

    private static WorkspaceControlDto toControl(ControlWorkspaceResult.WorkspaceControl control) {
        return new WorkspaceControlDto(
                control.id(),
                control.uid(),
                control.title(),
                control.descriptionPreview(),
                control.objectivePreview(),
                control.controlFunction(),
                control.status(),
                control.owner(),
                control.implementationScopePreview(),
                control.category(),
                control.source(),
                control.scopedImplementations().stream()
                        .map(scoped -> new WorkspaceScopedImplementationDto(
                                scoped.id(),
                                scoped.uid(),
                                scoped.name(),
                                scoped.implementationScope(),
                                scoped.operationalAssetId(),
                                scoped.operationalAssetUid(),
                                scoped.operationalAssetName()))
                        .toList(),
                control.tests().stream()
                        .map(test -> new WorkspaceControlTestDto(
                                test.id(),
                                test.uid(),
                                test.methodology(),
                                test.conclusion(),
                                test.testerIdentity(),
                                test.testDate(),
                                test.notesPreview()))
                        .toList(),
                control.assessments().stream()
                        .map(assessment -> new WorkspaceAssessmentDto(
                                assessment.id(),
                                assessment.uid(),
                                assessment.designEffectiveness(),
                                assessment.operatingEffectiveness(),
                                assessment.assessedAt(),
                                assessment.assessor(),
                                assessment.supportingTestIds()))
                        .toList(),
                control.evidence().stream()
                        .map(evidence -> new WorkspaceEvidenceDto(
                                evidence.id(),
                                evidence.uid(),
                                evidence.title(),
                                evidence.summaryPreview(),
                                evidence.evidenceType(),
                                evidence.derivedAt()))
                        .toList(),
                control.findings().stream()
                        .map(finding -> new WorkspaceFindingDto(
                                finding.id(),
                                finding.uid(),
                                finding.title(),
                                finding.findingType(),
                                finding.severity(),
                                finding.status(),
                                finding.owner(),
                                finding.dueDate()))
                        .toList(),
                control.riskMappings().stream()
                        .map(mapping -> new WorkspaceRiskMappingDto(
                                mapping.id(),
                                mapping.controlRole(),
                                mapping.targetIdentifier(),
                                mapping.targetTitle(),
                                mapping.mappingObjective(),
                                mapping.evidenceRefs().stream()
                                        .map(ref -> new WorkspaceMappingEvidenceRefDto(
                                                ref.evidenceRef(), ref.evidenceNotePreview(), ref.evidenceArtifactId()))
                                        .toList()))
                        .toList(),
                control.queueReasons());
    }

    public record WorkspaceControlDto(
            UUID id,
            String uid,
            String title,
            String descriptionPreview,
            String objectivePreview,
            ControlFunction controlFunction,
            ControlStatus status,
            String owner,
            String implementationScopePreview,
            String category,
            String source,
            List<WorkspaceScopedImplementationDto> scopedImplementations,
            List<WorkspaceControlTestDto> tests,
            List<WorkspaceAssessmentDto> assessments,
            List<WorkspaceEvidenceDto> evidence,
            List<WorkspaceFindingDto> findings,
            List<WorkspaceRiskMappingDto> riskMappings,
            List<String> queueReasons) {}

    public record WorkspaceScopedImplementationDto(
            UUID id,
            String uid,
            String name,
            String implementationScope,
            UUID operationalAssetId,
            String operationalAssetUid,
            String operationalAssetName) {}

    public record WorkspaceControlTestDto(
            UUID id,
            String uid,
            String methodology,
            ControlTestConclusion conclusion,
            String testerIdentity,
            LocalDate testDate,
            String notesPreview) {}

    public record WorkspaceAssessmentDto(
            UUID id,
            String uid,
            String designEffectiveness,
            String operatingEffectiveness,
            LocalDate assessedAt,
            String assessor,
            List<String> supportingTestIds) {}

    public record WorkspaceEvidenceDto(
            UUID id, String uid, String title, String summaryPreview, String evidenceType, Instant derivedAt) {}

    public record WorkspaceFindingDto(
            UUID id,
            String uid,
            String title,
            String findingType,
            String severity,
            String status,
            String owner,
            LocalDate dueDate) {}

    public record WorkspaceRiskMappingDto(
            UUID id,
            String controlRole,
            String targetIdentifier,
            String targetTitle,
            String mappingObjective,
            List<WorkspaceMappingEvidenceRefDto> evidenceRefs) {}

    public record WorkspaceMappingEvidenceRefDto(
            String evidenceRef, String evidenceNotePreview, UUID evidenceArtifactId) {}
}
