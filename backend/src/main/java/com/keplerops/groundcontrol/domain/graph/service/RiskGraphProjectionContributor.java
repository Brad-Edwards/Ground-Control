package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RiskGraphProjectionContributor implements GraphProjectionContributor {

    private final RiskScenarioRepository riskScenarioRepository;
    private final RiskScenarioLinkRepository riskScenarioLinkRepository;

    public RiskGraphProjectionContributor(
            RiskScenarioRepository riskScenarioRepository, RiskScenarioLinkRepository riskScenarioLinkRepository) {
        this.riskScenarioRepository = riskScenarioRepository;
        this.riskScenarioLinkRepository = riskScenarioLinkRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        return riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(scenario -> scenario.getStatus() != RiskScenarioStatus.ARCHIVED)
                .map(scenario -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("title", scenario.getTitle());
                    properties.put("status", scenario.getStatus().name());
                    properties.put("threat", scenario.getThreat());
                    properties.put("method", scenario.getMethod());
                    properties.put("asset", scenario.getAsset());
                    properties.put("effect", scenario.getEffect());
                    properties.put("timeHorizon", scenario.getTimeHorizon());
                    properties.put("createdBy", scenario.getCreatedBy());
                    return new GraphNode(
                            GraphIds.nodeId(GraphEntityType.RISK_SCENARIO, scenario.getId()),
                            scenario.getId().toString(),
                            GraphEntityType.RISK_SCENARIO,
                            scenario.getProject().getIdentifier(),
                            scenario.getUid(),
                            scenario.getUid(),
                            properties);
                })
                .toList();
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        return riskScenarioLinkRepository.findByProjectId(projectId).stream()
                .map(link -> toScenarioLinkEdge(
                        link.getId(),
                        link.getRiskScenario().getId(),
                        link.getTargetType(),
                        link.getTargetEntityId(),
                        link.getLinkType().name()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private GraphEdge toScenarioLinkEdge(
            UUID linkId, UUID scenarioId, RiskScenarioLinkTargetType targetType, UUID targetEntityId, String edgeType) {
        if (targetEntityId == null) {
            return null;
        }
        var targetEntityType =
                switch (targetType) {
                    case OBSERVATION -> GraphEntityType.OBSERVATION;
                    case ASSET -> GraphEntityType.OPERATIONAL_ASSET;
                    case REQUIREMENT -> GraphEntityType.REQUIREMENT;
                    case CONTROL -> GraphEntityType.CONTROL;
                    case THREAT_MODEL -> GraphEntityType.THREAT_MODEL;
                    case FINDING -> GraphEntityType.FINDING;
                    case AUDIT_RECORD -> GraphEntityType.AUDIT;
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
                            VULNERABILITY,
                            EXTERNAL -> null;
                };
        if (targetEntityType == null) {
            return null;
        }
        return new GraphEdge(
                linkId.toString(),
                edgeType,
                GraphIds.nodeId(GraphEntityType.RISK_SCENARIO, scenarioId),
                GraphIds.nodeId(targetEntityType, targetEntityId),
                GraphEntityType.RISK_SCENARIO,
                targetEntityType,
                Map.of());
    }
}
