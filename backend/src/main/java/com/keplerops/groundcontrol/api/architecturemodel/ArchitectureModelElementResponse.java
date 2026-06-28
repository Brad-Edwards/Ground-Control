package com.keplerops.groundcontrol.api.architecturemodel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElement;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementView;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelProvenanceSource;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArchitectureModelElementResponse(
        UUID id,
        String graphNodeId,
        String stableKey,
        ArchitectureModelElementKind elementKind,
        UUID snapshotId,
        String modelVersion,
        String label,
        String summary,
        String sourcePath,
        String trustBoundaryKey,
        String dataClassificationKey,
        String flowSourceStableKey,
        String flowTargetStableKey,
        ArchitectureFlowDirection flowDirection,
        ArchitectureModelProvenanceSource provenanceSource,
        String provenanceKey,
        String adapterId,
        String toolName,
        String toolVersion,
        String rulesetName,
        String rulesetVersion,
        UUID derivationRunId,
        String commitSha,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public static ArchitectureModelElementResponse from(ArchitectureModelElementView view) {
        return from(view.element(), view.currentState());
    }

    public static ArchitectureModelElementResponse from(
            ArchitectureModelElement element, ArchitectureModelElementState state) {
        if (state == null) {
            return new ArchitectureModelElementResponse(
                    element.getId(),
                    GraphIds.nodeId(GraphEntityType.ARCHITECTURE_MODEL_ELEMENT, element.getId()),
                    element.getStableKey(),
                    element.getElementKind(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    element.getCreatedAt(),
                    element.getUpdatedAt());
        }
        return new ArchitectureModelElementResponse(
                element.getId(),
                GraphIds.nodeId(GraphEntityType.ARCHITECTURE_MODEL_ELEMENT, element.getId()),
                state.getStableKey(),
                state.getElementKind(),
                state.getSnapshot().getId(),
                state.getSnapshot().getModelVersion(),
                state.getLabel(),
                state.getSummary(),
                state.getSourcePath(),
                state.getTrustBoundaryKey(),
                state.getDataClassificationKey(),
                state.getFlowSourceStableKey(),
                state.getFlowTargetStableKey(),
                state.getFlowDirection(),
                state.getProvenanceSource(),
                state.getProvenanceKey(),
                state.getAdapterId(),
                state.getToolName(),
                state.getToolVersion(),
                state.getRulesetName(),
                state.getRulesetVersion(),
                state.getDerivationRunId(),
                state.getCommitSha(),
                state.getMetadata(),
                state.getCreatedAt(),
                state.getUpdatedAt());
    }
}
