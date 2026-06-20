package com.keplerops.groundcontrol.domain.controls.service;

import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only composition for the Control and Assurance Workspace per GC-Q011.
 *
 * <p>The workspace exposes bounded summaries and links across the control
 * catalog, scoped implementations, tests, effectiveness assessments, evidence
 * artifacts, findings, and risk mappings. It does not create a second control
 * assurance aggregate or leak raw evidence payloads.
 */
public record ControlWorkspaceResult(List<WorkspaceControl> controls) {

    public int controlCount() {
        return controls.size();
    }

    public record WorkspaceControl(
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
            List<WorkspaceScopedImplementation> scopedImplementations,
            List<WorkspaceControlTest> tests,
            List<WorkspaceAssessment> assessments,
            List<WorkspaceEvidence> evidence,
            List<WorkspaceFinding> findings,
            List<WorkspaceRiskMapping> riskMappings,
            List<String> queueReasons) {}

    public record WorkspaceScopedImplementation(
            UUID id,
            String uid,
            String name,
            String implementationScope,
            UUID operationalAssetId,
            String operationalAssetUid,
            String operationalAssetName) {}

    public record WorkspaceControlTest(
            UUID id,
            String uid,
            String methodology,
            ControlTestConclusion conclusion,
            String testerIdentity,
            LocalDate testDate,
            String notesPreview) {}

    public record WorkspaceAssessment(
            UUID id,
            String uid,
            String designEffectiveness,
            String operatingEffectiveness,
            LocalDate assessedAt,
            String assessor,
            List<String> supportingTestIds) {}

    public record WorkspaceEvidence(
            UUID id, String uid, String title, String summaryPreview, String evidenceType, Instant derivedAt) {}

    public record WorkspaceFinding(
            UUID id,
            String uid,
            String title,
            String findingType,
            String severity,
            String status,
            String owner,
            LocalDate dueDate) {}

    public record WorkspaceRiskMapping(
            UUID id,
            String controlRole,
            String targetIdentifier,
            String targetTitle,
            String mappingObjective,
            List<WorkspaceMappingEvidenceRef> evidenceRefs) {}

    public record WorkspaceMappingEvidenceRef(
            String evidenceRef, String evidenceNotePreview, UUID evidenceArtifactId) {}
}
