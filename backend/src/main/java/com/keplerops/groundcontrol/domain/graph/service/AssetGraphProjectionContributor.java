package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AssetGraphProjectionContributor implements GraphProjectionContributor {

    private final OperationalAssetRepository assetRepository;
    private final ObservationRepository observationRepository;
    private final AssetRelationRepository assetRelationRepository;
    private final AssetLinkRepository assetLinkRepository;

    public AssetGraphProjectionContributor(
            OperationalAssetRepository assetRepository,
            ObservationRepository observationRepository,
            AssetRelationRepository assetRelationRepository,
            AssetLinkRepository assetLinkRepository) {
        this.assetRepository = assetRepository;
        this.observationRepository = observationRepository;
        this.assetRelationRepository = assetRelationRepository;
        this.assetLinkRepository = assetLinkRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        var assetNodes = assetRepository.findByProjectIdAndArchivedAtIsNull(projectId).stream()
                .map(asset -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("title", asset.getName());
                    properties.put("name", asset.getName());
                    properties.put("description", asset.getDescription());
                    properties.put("assetType", asset.getAssetType().name());
                    properties.put("owner", asset.getOwner());
                    properties.put("steward", asset.getSteward());
                    properties.put(
                            "environment",
                            asset.getEnvironment() == null
                                    ? null
                                    : asset.getEnvironment().name());
                    properties.put(
                            "criticality",
                            asset.getCriticality() == null
                                    ? null
                                    : asset.getCriticality().name());
                    properties.put("businessContext", asset.getBusinessContext());
                    properties.put(
                            "scopeDesignation",
                            asset.getScopeDesignation() == null
                                    ? null
                                    : asset.getScopeDesignation().name());
                    // GC-M011: subtype is a queryable graph facet alongside
                    // assetType. Metadata is intentionally NOT projected —
                    // free-form, high-cardinality, and may carry per-record
                    // detail unsafe for graph-wide indexing.
                    properties.put("subtype", asset.getSubtype());
                    // GC-M018: knowledge state — risk / threat / control
                    // workflows reading the graph must be able to distinguish
                    // CONFIRMED from PROVISIONAL from UNKNOWN without a
                    // second persisted aggregate.
                    properties.put(
                            "knowledgeState",
                            asset.getKnowledgeState() == null
                                    ? null
                                    : asset.getKnowledgeState().name());
                    properties.put("archivedAt", asset.getArchivedAt());
                    return new GraphNode(
                            GraphIds.nodeId(GraphEntityType.OPERATIONAL_ASSET, asset.getId()),
                            asset.getId().toString(),
                            GraphEntityType.OPERATIONAL_ASSET,
                            asset.getProject().getIdentifier(),
                            asset.getUid(),
                            asset.getUid(),
                            properties);
                })
                .toList();
        var observationNodes = observationRepository.findByProjectId(projectId).stream()
                .map(observation -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("title", observation.getObservationKey());
                    properties.put("category", observation.getCategory().name());
                    properties.put("observationKey", observation.getObservationKey());
                    properties.put("observationValue", observation.getObservationValue());
                    properties.put("source", observation.getSource());
                    properties.put("observedAt", observation.getObservedAt());
                    properties.put("expiresAt", observation.getExpiresAt());
                    properties.put("confidence", observation.getConfidence());
                    properties.put("evidenceRef", observation.getEvidenceRef());
                    return new GraphNode(
                            GraphIds.nodeId(GraphEntityType.OBSERVATION, observation.getId()),
                            observation.getId().toString(),
                            GraphEntityType.OBSERVATION,
                            observation.getAsset().getProject().getIdentifier(),
                            null,
                            observation.getObservationKey(),
                            properties);
                })
                .toList();
        return java.util.stream.Stream.concat(assetNodes.stream(), observationNodes.stream())
                .toList();
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        var relationEdges = assetRelationRepository.findActiveByProjectId(projectId).stream()
                .map(relation -> {
                    // LinkedHashMap because the GC-M018 edge property set is
                    // open-ended ({@code knowledgeState} joins {@code
                    // createdAt}, and a future edge attribute would extend
                    // the same map). Map.of(...) is fixed-arity and would
                    // need to be re-written for every new property.
                    Map<String, Object> edgeProperties = new LinkedHashMap<>();
                    edgeProperties.put("createdAt", relation.getCreatedAt());
                    edgeProperties.put(
                            "knowledgeState",
                            relation.getKnowledgeState() == null
                                    ? null
                                    : relation.getKnowledgeState().name());
                    return new GraphEdge(
                            relation.getId().toString(),
                            relation.getRelationType().name(),
                            GraphIds.nodeId(
                                    GraphEntityType.OPERATIONAL_ASSET,
                                    relation.getSource().getId()),
                            GraphIds.nodeId(
                                    GraphEntityType.OPERATIONAL_ASSET,
                                    relation.getTarget().getId()),
                            GraphEntityType.OPERATIONAL_ASSET,
                            GraphEntityType.OPERATIONAL_ASSET,
                            edgeProperties);
                })
                .toList();

        var observationEdges = observationRepository.findByProjectId(projectId).stream()
                .map(observation -> new GraphEdge(
                        "OBSERVED_ON:" + observation.getId() + ":"
                                + observation.getAsset().getId(),
                        "OBSERVED_ON",
                        GraphIds.nodeId(GraphEntityType.OBSERVATION, observation.getId()),
                        GraphIds.nodeId(
                                GraphEntityType.OPERATIONAL_ASSET,
                                observation.getAsset().getId()),
                        GraphEntityType.OBSERVATION,
                        GraphEntityType.OPERATIONAL_ASSET,
                        Map.of("observedAt", observation.getObservedAt())))
                .toList();

        var internalLinkEdges = assetLinkRepository.findByProjectId(projectId).stream()
                .map(link -> toInternalLinkEdge(
                        link.getId(),
                        link.getAsset().getId(),
                        link.getTargetType(),
                        link.getTargetEntityId(),
                        link.getLinkType().name()))
                .filter(java.util.Objects::nonNull)
                .toList();

        return java.util.stream.Stream.of(relationEdges, observationEdges, internalLinkEdges)
                .flatMap(List::stream)
                .toList();
    }

    private GraphEdge toInternalLinkEdge(
            UUID linkId, UUID assetId, AssetLinkTargetType targetType, UUID targetEntityId, String edgeType) {
        if (targetEntityId == null) {
            return null;
        }
        var targetEntityType =
                switch (targetType) {
                    case REQUIREMENT -> GraphEntityType.REQUIREMENT;
                    case RISK_SCENARIO -> GraphEntityType.RISK_SCENARIO;
                    case CONTROL -> GraphEntityType.CONTROL;
                        // Always emits an edge to the THREAT_MODEL node regardless of the
                        // threat model's status. ThreatModelGraphProjectionContributor
                        // intentionally keeps archived threat-model nodes in the graph
                        // (see its contributeNodes javadoc) so this edge never dangles.
                    case THREAT_MODEL_ENTRY -> GraphEntityType.THREAT_MODEL;
                        // Always emits an edge to the FINDING node regardless of the
                        // finding's status. FindingGraphProjectionContributor intentionally
                        // keeps VERIFIED_CLOSED finding nodes in the graph (see its
                        // contributeNodes javadoc) so this edge never dangles.
                    case FINDING -> GraphEntityType.FINDING;
                        // Always emits an edge to the AUDIT node. AuditGraphProjectionContributor
                        // keeps all audit nodes in the graph regardless of status so this edge
                        // never dangles.
                    case AUDIT -> GraphEntityType.AUDIT;
                        // Always emits an edge to the EVIDENCE_ARTIFACT node.
                        // EvidenceArtifactGraphProjectionContributor projects every
                        // evidence artifact (current and superseded) so this edge
                        // never dangles.
                    case EVIDENCE -> GraphEntityType.EVIDENCE_ARTIFACT;
                        // RISK_REGISTER_RECORD, RISK_ASSESSMENT_RESULT, TREATMENT_PLAN, and
                        // METHODOLOGY_PROFILE are retired target types (ADR-089) with no backing
                        // graph node; never emit an edge for them.
                    case RISK_REGISTER_RECORD,
                            RISK_ASSESSMENT_RESULT,
                            TREATMENT_PLAN,
                            METHODOLOGY_PROFILE,
                            ISSUE,
                            CODE,
                            CONFIGURATION,
                            EXTERNAL -> null;
                };
        if (targetEntityType == null) {
            return null;
        }
        return new GraphEdge(
                linkId.toString(),
                edgeType,
                GraphIds.nodeId(GraphEntityType.OPERATIONAL_ASSET, assetId),
                GraphIds.nodeId(targetEntityType, targetEntityId),
                GraphEntityType.OPERATIONAL_ASSET,
                targetEntityType,
                Map.of());
    }
}
