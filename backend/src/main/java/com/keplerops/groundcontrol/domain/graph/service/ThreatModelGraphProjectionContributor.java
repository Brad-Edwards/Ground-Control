package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelLinkRepository;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ThreatModelGraphProjectionContributor implements GraphProjectionContributor {

    private final ThreatModelRepository threatModelRepository;
    private final ThreatModelLinkRepository threatModelLinkRepository;
    private final OperationalAssetRepository operationalAssetRepository;
    private final RequirementRepository requirementRepository;
    private final RiskScenarioRepository riskScenarioRepository;

    public ThreatModelGraphProjectionContributor(
            ThreatModelRepository threatModelRepository,
            ThreatModelLinkRepository threatModelLinkRepository,
            OperationalAssetRepository operationalAssetRepository,
            RequirementRepository requirementRepository,
            RiskScenarioRepository riskScenarioRepository) {
        this.threatModelRepository = threatModelRepository;
        this.threatModelLinkRepository = threatModelLinkRepository;
        this.operationalAssetRepository = operationalAssetRepository;
        this.requirementRepository = requirementRepository;
        this.riskScenarioRepository = riskScenarioRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        // Include all threat models regardless of status. Archived threat models
        // remain in the graph as historical evidence with `status=ARCHIVED` in
        // their node properties — frontends can filter visually. Filtering
        // archived nodes here would create dangling edges from
        // AssetGraphProjectionContributor and RiskGraphProjectionContributor,
        // which both project incoming THREAT_MODEL edges without status checks.
        return threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(tm -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("title", tm.getTitle());
                    properties.put("status", tm.getStatus().name());
                    properties.put("threatSource", tm.getThreatSource());
                    properties.put("threatEvent", tm.getThreatEvent());
                    properties.put("effect", tm.getEffect());
                    if (tm.getStride() != null) {
                        properties.put("stride", tm.getStride().name());
                    }
                    if (tm.getNarrative() != null) {
                        properties.put("narrative", tm.getNarrative());
                    }
                    if (tm.getCreatedBy() != null) {
                        properties.put("createdBy", tm.getCreatedBy());
                    }
                    return new GraphNode(
                            GraphIds.nodeId(GraphEntityType.THREAT_MODEL, tm.getId()),
                            tm.getId().toString(),
                            GraphEntityType.THREAT_MODEL,
                            tm.getProject().getIdentifier(),
                            tm.getUid(),
                            tm.getUid(),
                            properties);
                })
                .toList();
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        // Skip edges to archived targets so the projection never produces dangling
        // edges. AssetGraphProjectionContributor and RequirementGraphProjectionContributor
        // omit archived nodes (`findByProjectIdAndArchivedAtIsNull`), and
        // RiskGraphProjectionContributor omits ARCHIVED scenarios in its node stream;
        // any THREAT_MODEL → ASSET/REQUIREMENT/RISK_SCENARIO edge whose target is no
        // longer in the node set would orphan in `gc_get_graph_visualization` / AGE
        // materialization. Other internal targets (CONTROL, OBSERVATION,
        // RISK_ASSESSMENT_RESULT, VERIFICATION_RESULT) are not archived in their own
        // contributors, so they require no filter here.
        Set<UUID> liveAssetIds =
                Set.copyOf(operationalAssetRepository.findIdsByProjectIdAndArchivedAtIsNull(projectId));
        Set<UUID> liveRequirementIds =
                Set.copyOf(requirementRepository.findIdsByProjectIdAndArchivedAtIsNull(projectId));
        Set<UUID> liveRiskScenarioIds = Set.copyOf(
                riskScenarioRepository.findIdsByProjectIdAndStatusNot(projectId, RiskScenarioStatus.ARCHIVED));

        return threatModelLinkRepository.findByProjectId(projectId).stream()
                .map(link -> toThreatModelLinkEdge(
                        link.getId(),
                        link.getThreatModel().getId(),
                        link.getTargetType(),
                        link.getTargetEntityId(),
                        link.getLinkType().name(),
                        liveAssetIds,
                        liveRequirementIds,
                        liveRiskScenarioIds))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private GraphEdge toThreatModelLinkEdge(
            UUID linkId,
            UUID threatModelId,
            ThreatModelLinkTargetType targetType,
            UUID targetEntityId,
            String edgeType,
            Set<UUID> liveAssetIds,
            Set<UUID> liveRequirementIds,
            Set<UUID> liveRiskScenarioIds) {
        if (targetEntityId == null) {
            return null;
        }
        var targetEntityType =
                switch (targetType) {
                    case ASSET -> GraphEntityType.OPERATIONAL_ASSET;
                    case REQUIREMENT -> GraphEntityType.REQUIREMENT;
                    case CONTROL -> GraphEntityType.CONTROL;
                    case RISK_SCENARIO -> GraphEntityType.RISK_SCENARIO;
                    case OBSERVATION -> GraphEntityType.OBSERVATION;
                    case VERIFICATION_RESULT -> GraphEntityType.VERIFICATION_RESULT;
                    case FINDING -> GraphEntityType.FINDING;
                        // Always emits an edge to the EVIDENCE_ARTIFACT node.
                        // EvidenceArtifactGraphProjectionContributor projects every
                        // evidence artifact (current and superseded) so this edge
                        // never dangles.
                    case EVIDENCE -> GraphEntityType.EVIDENCE_ARTIFACT;
                        // RISK_ASSESSMENT_RESULT and ARCHITECTURE_MODEL are retired target types
                        // (ADR-089) with no backing graph node; never emit an edge for them.
                    case RISK_ASSESSMENT_RESULT, ARCHITECTURE_MODEL, CODE, ISSUE, EXTERNAL -> null;
                };
        if (targetEntityType == null) {
            return null;
        }
        boolean targetIsLive =
                switch (targetType) {
                    case ASSET -> liveAssetIds.contains(targetEntityId);
                    case REQUIREMENT -> liveRequirementIds.contains(targetEntityId);
                    case RISK_SCENARIO -> liveRiskScenarioIds.contains(targetEntityId);
                    default -> true;
                };
        if (!targetIsLive) {
            return null;
        }
        return new GraphEdge(
                linkId.toString(),
                edgeType,
                GraphIds.nodeId(GraphEntityType.THREAT_MODEL, threatModelId),
                GraphIds.nodeId(targetEntityType, targetEntityId),
                GraphEntityType.THREAT_MODEL,
                targetEntityType,
                Map.of());
    }
}
