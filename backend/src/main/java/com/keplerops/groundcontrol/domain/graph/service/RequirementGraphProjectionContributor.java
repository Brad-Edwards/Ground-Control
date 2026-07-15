package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class RequirementGraphProjectionContributor implements GraphProjectionContributor {

    private final RequirementRepository requirementRepository;
    private final RequirementRelationRepository relationRepository;
    private final TraceabilityLinkRepository traceabilityLinkRepository;
    private final ControlRepository controlRepository;
    private final RiskScenarioRepository riskScenarioRepository;

    public RequirementGraphProjectionContributor(
            RequirementRepository requirementRepository,
            RequirementRelationRepository relationRepository,
            TraceabilityLinkRepository traceabilityLinkRepository,
            ControlRepository controlRepository,
            RiskScenarioRepository riskScenarioRepository) {
        this.requirementRepository = requirementRepository;
        this.relationRepository = relationRepository;
        this.traceabilityLinkRepository = traceabilityLinkRepository;
        this.controlRepository = controlRepository;
        this.riskScenarioRepository = riskScenarioRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        var nodes = new ArrayList<GraphNode>();
        requirementRepository.findByProjectIdAndArchivedAtIsNull(projectId).stream()
                .map(requirement -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("title", requirement.getTitle());
                    properties.put("statement", requirement.getStatement());
                    properties.put("priority", requirement.getPriority().name());
                    properties.put("status", requirement.getStatus().name());
                    properties.put(
                            "requirementType", requirement.getRequirementType().name());
                    properties.put("wave", requirement.getWave());
                    return new GraphNode(
                            GraphIds.nodeId(GraphEntityType.REQUIREMENT, requirement.getId()),
                            requirement.getId().toString(),
                            GraphEntityType.REQUIREMENT,
                            requirement.getProject().getIdentifier(),
                            requirement.getUid(),
                            requirement.getUid(),
                            properties);
                })
                .forEach(nodes::add);
        nodes.addAll(loadTraceabilityProjection(projectId).nodes());
        return List.copyOf(nodes);
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        var edges = new ArrayList<GraphEdge>();
        relationRepository.findActiveWithSourceAndTargetByProjectId(projectId).stream()
                .map(relation -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("createdAt", relation.getCreatedAt());
                    properties.put("sourceUid", relation.getSource().getUid());
                    properties.put("targetUid", relation.getTarget().getUid());
                    return new GraphEdge(
                            relation.getId().toString(),
                            relation.getRelationType().name(),
                            GraphIds.nodeId(
                                    GraphEntityType.REQUIREMENT,
                                    relation.getSource().getId()),
                            GraphIds.nodeId(
                                    GraphEntityType.REQUIREMENT,
                                    relation.getTarget().getId()),
                            GraphEntityType.REQUIREMENT,
                            GraphEntityType.REQUIREMENT,
                            properties);
                })
                .forEach(edges::add);
        edges.addAll(loadTraceabilityProjection(projectId).edges());
        return List.copyOf(edges);
    }

    private TraceabilityProjection loadTraceabilityProjection(UUID projectId) {
        var links = traceabilityLinkRepository.findLiveRequirementLinksByProjectId(projectId);
        var controlsByUid = controlsByUid(projectId, links);
        var risksByUid = risksByUid(projectId, links);
        var artifactNodes = new LinkedHashMap<String, GraphNode>();
        var traceabilityEdges = new ArrayList<GraphEdge>();

        for (var link : links) {
            Target target = resolveTarget(projectId, link, controlsByUid, risksByUid);
            if (target.referenceNode() != null) {
                artifactNodes.putIfAbsent(target.id(), target.referenceNode());
            }
            traceabilityEdges.add(toTraceabilityEdge(link, target));
        }
        return new TraceabilityProjection(List.copyOf(artifactNodes.values()), List.copyOf(traceabilityEdges));
    }

    private Map<String, Control> controlsByUid(UUID projectId, List<TraceabilityLink> links) {
        Set<String> uids = identifiersFor(links, ArtifactType.CONTROL);
        if (uids.isEmpty()) {
            return Map.of();
        }
        return controlRepository.findByProjectIdAndUidIn(projectId, uids).stream()
                .collect(Collectors.toMap(Control::getUid, Function.identity()));
    }

    private Map<String, RiskScenario> risksByUid(UUID projectId, List<TraceabilityLink> links) {
        Set<String> uids = identifiersFor(links, ArtifactType.RISK_SCENARIO);
        if (uids.isEmpty()) {
            return Map.of();
        }
        return riskScenarioRepository.findByProjectIdAndUidIn(projectId, uids).stream()
                .filter(risk -> risk.getStatus() != RiskScenarioStatus.ARCHIVED)
                .collect(Collectors.toMap(RiskScenario::getUid, Function.identity()));
    }

    private Set<String> identifiersFor(List<TraceabilityLink> links, ArtifactType artifactType) {
        return links.stream()
                .filter(link -> link.getArtifactType() == artifactType)
                .map(TraceabilityLink::getArtifactIdentifier)
                .collect(Collectors.toSet());
    }

    private Target resolveTarget(
            UUID projectId,
            TraceabilityLink link,
            Map<String, Control> controlsByUid,
            Map<String, RiskScenario> risksByUid) {
        if (link.getArtifactType() == ArtifactType.CONTROL) {
            var control = controlsByUid.get(link.getArtifactIdentifier());
            if (control != null) {
                return new Target(
                        GraphIds.nodeId(GraphEntityType.CONTROL, control.getId()), GraphEntityType.CONTROL, null);
            }
        } else if (link.getArtifactType() == ArtifactType.RISK_SCENARIO) {
            var risk = risksByUid.get(link.getArtifactIdentifier());
            if (risk != null) {
                return new Target(
                        GraphIds.nodeId(GraphEntityType.RISK_SCENARIO, risk.getId()),
                        GraphEntityType.RISK_SCENARIO,
                        null);
            }
        }
        return artifactReferenceTarget(projectId, link);
    }

    private Target artifactReferenceTarget(UUID projectId, TraceabilityLink link) {
        String id = GraphIds.artifactReferenceNodeId(projectId, link.getArtifactType(), link.getArtifactIdentifier());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("artifactType", link.getArtifactType().name());
        properties.put("artifactIdentifier", link.getArtifactIdentifier());
        var node = new GraphNode(
                id,
                id.substring(id.indexOf(':') + 1),
                GraphEntityType.ARTIFACT_REFERENCE,
                link.getRequirement().getProject().getIdentifier(),
                link.getArtifactIdentifier(),
                link.getArtifactIdentifier(),
                properties);
        return new Target(id, GraphEntityType.ARTIFACT_REFERENCE, node);
    }

    private GraphEdge toTraceabilityEdge(TraceabilityLink link, Target target) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("artifactType", link.getArtifactType().name());
        properties.put("artifactIdentifier", link.getArtifactIdentifier());
        return new GraphEdge(
                link.getId().toString(),
                link.getLinkType().name(),
                GraphIds.nodeId(
                        GraphEntityType.REQUIREMENT, link.getRequirement().getId()),
                target.id(),
                GraphEntityType.REQUIREMENT,
                target.entityType(),
                properties);
    }

    private record Target(String id, GraphEntityType entityType, GraphNode referenceNode) {}

    private record TraceabilityProjection(List<GraphNode> nodes, List<GraphEdge> edges) {}
}
