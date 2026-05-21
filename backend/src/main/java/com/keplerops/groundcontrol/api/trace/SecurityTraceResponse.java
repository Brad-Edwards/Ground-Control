package com.keplerops.groundcontrol.api.trace;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.state.AssetCriticality;
import com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment;
import com.keplerops.groundcontrol.domain.assets.state.AssetScope;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.state.KnowledgeState;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.RequirementType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import com.keplerops.groundcontrol.domain.requirements.state.SyncStatus;
import com.keplerops.groundcontrol.domain.trace.SecurityTrace;
import com.keplerops.groundcontrol.domain.trace.SecurityTraceSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API response for the {@code GET /{id}/trace} endpoints on threat models and
 * risk scenarios. Maps the composite {@link SecurityTrace} domain read-model
 * to a flat, serialisation-friendly shape.
 */
public record SecurityTraceResponse(
        SecurityTraceSourceType sourceType,
        UUID sourceId,
        String sourceUid,
        String sourceTitle,
        List<TracedAsset> assets,
        List<TracedControl> controls,
        List<TracedRequirement> requirements) {

    public static SecurityTraceResponse from(SecurityTrace trace) {
        return new SecurityTraceResponse(
                trace.sourceType(),
                trace.sourceId(),
                trace.sourceUid(),
                trace.sourceTitle(),
                trace.assets().stream().map(TracedAsset::from).toList(),
                trace.controls().stream().map(TracedControl::from).toList(),
                trace.requirements().stream().map(TracedRequirement::from).toList());
    }

    // -----------------------------------------------------------------------
    // Nested response records
    // -----------------------------------------------------------------------

    public record TracedAsset(
            UUID id,
            String graphNodeId,
            String projectIdentifier,
            String uid,
            String name,
            String description,
            AssetType assetType,
            String owner,
            String steward,
            AssetEnvironment environment,
            AssetCriticality criticality,
            String businessContext,
            AssetScope scopeDesignation,
            String subtype,
            Map<String, Object> metadata,
            KnowledgeState knowledgeState,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static TracedAsset from(OperationalAsset asset) {
            return new TracedAsset(
                    asset.getId(),
                    GraphIds.nodeId(GraphEntityType.OPERATIONAL_ASSET, asset.getId()),
                    asset.getProject().getIdentifier(),
                    asset.getUid(),
                    asset.getName(),
                    asset.getDescription(),
                    asset.getAssetType(),
                    asset.getOwner(),
                    asset.getSteward(),
                    asset.getEnvironment(),
                    asset.getCriticality(),
                    asset.getBusinessContext(),
                    asset.getScopeDesignation(),
                    asset.getSubtype(),
                    asset.getMetadata(),
                    asset.getKnowledgeState(),
                    asset.getArchivedAt(),
                    asset.getCreatedAt(),
                    asset.getUpdatedAt());
        }
    }

    public record TracedControl(
            UUID id,
            String graphNodeId,
            String projectIdentifier,
            String uid,
            String title,
            String description,
            String objective,
            ControlFunction controlFunction,
            ControlStatus status,
            String owner,
            String implementationScope,
            Map<String, Object> methodologyFactors,
            Map<String, Object> effectiveness,
            String category,
            String source,
            Instant createdAt,
            Instant updatedAt) {

        public static TracedControl from(Control control) {
            return new TracedControl(
                    control.getId(),
                    GraphIds.nodeId(GraphEntityType.CONTROL, control.getId()),
                    control.getProject().getIdentifier(),
                    control.getUid(),
                    control.getTitle(),
                    control.getDescription(),
                    control.getObjective(),
                    control.getControlFunction(),
                    control.getStatus(),
                    control.getOwner(),
                    control.getImplementationScope(),
                    control.getMethodologyFactors(),
                    control.getEffectiveness(),
                    control.getCategory(),
                    control.getSource(),
                    control.getCreatedAt(),
                    control.getUpdatedAt());
        }
    }

    public record TracedRequirement(TracedRequirementDetail requirement, List<TracedArtifact> artifacts) {

        public static TracedRequirement from(SecurityTrace.RequirementTrace rt) {
            return new TracedRequirement(
                    TracedRequirementDetail.from(rt.requirement()),
                    rt.artifacts().stream().map(TracedArtifact::from).toList());
        }
    }

    public record TracedRequirementDetail(
            UUID id,
            String graphNodeId,
            String uid,
            String projectIdentifier,
            String title,
            String statement,
            String rationale,
            RequirementType requirementType,
            Priority priority,
            Status status,
            Integer wave,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt) {

        public static TracedRequirementDetail from(Requirement r) {
            return new TracedRequirementDetail(
                    r.getId(),
                    GraphIds.nodeId(GraphEntityType.REQUIREMENT, r.getId()),
                    r.getUid(),
                    r.getProject() != null ? r.getProject().getIdentifier() : null,
                    r.getTitle(),
                    r.getStatement(),
                    r.getRationale(),
                    r.getRequirementType(),
                    r.getPriority(),
                    r.getStatus(),
                    r.getWave(),
                    r.getCreatedAt(),
                    r.getUpdatedAt(),
                    r.getArchivedAt());
        }
    }

    public record TracedArtifact(
            UUID id,
            UUID requirementId,
            ArtifactType artifactType,
            String artifactIdentifier,
            String artifactUrl,
            String artifactTitle,
            LinkType linkType,
            SyncStatus syncStatus,
            Instant lastSyncedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static TracedArtifact from(TraceabilityLink link) {
            return new TracedArtifact(
                    link.getId(),
                    link.getRequirement().getId(),
                    link.getArtifactType(),
                    link.getArtifactIdentifier(),
                    link.getArtifactUrl(),
                    link.getArtifactTitle(),
                    link.getLinkType(),
                    link.getSyncStatus(),
                    link.getLastSyncedAt(),
                    link.getCreatedAt(),
                    link.getUpdatedAt());
        }
    }
}
