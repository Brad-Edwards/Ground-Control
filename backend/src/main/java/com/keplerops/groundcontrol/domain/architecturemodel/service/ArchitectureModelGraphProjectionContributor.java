package com.keplerops.groundcontrol.domain.architecturemodel.service;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementStateRepository;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.service.GraphProjectionContributor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ArchitectureModelGraphProjectionContributor implements GraphProjectionContributor {

    private final ArchitectureModelElementStateRepository stateRepository;

    public ArchitectureModelGraphProjectionContributor(ArchitectureModelElementStateRepository stateRepository) {
        this.stateRepository = stateRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        return latestStates(projectId).stream().map(this::toNode).toList();
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        var byStableKey = latestStates(projectId).stream()
                .collect(Collectors.toMap(
                        ArchitectureModelElementState::getStableKey,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        return byStableKey.values().stream()
                .filter(state -> state.getElementKind() == ArchitectureModelElementKind.DATA_FLOW)
                .map(state -> toFlowEdge(state, byStableKey))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<ArchitectureModelElementState> latestStates(UUID projectId) {
        return stateRepository.findLatestSnapshotStatesByProjectId(projectId);
    }

    private GraphNode toNode(ArchitectureModelElementState state) {
        var element = state.getElement();
        var properties = new LinkedHashMap<String, Object>();
        properties.put("elementKind", state.getElementKind().name());
        properties.put("modelVersion", state.getSnapshot().getModelVersion());
        properties.put("schemaVersion", state.getSnapshot().getSchemaVersion());
        putIfPresent(properties, "summary", state.getSummary());
        putIfPresent(properties, "sourcePath", state.getSourcePath());
        putIfPresent(properties, "trustBoundaryKey", state.getTrustBoundaryKey());
        putIfPresent(properties, "dataClassificationKey", state.getDataClassificationKey());
        putIfPresent(
                properties,
                "flowDirection",
                state.getFlowDirection() == null
                        ? null
                        : state.getFlowDirection().name());
        properties.put("provenanceSource", state.getProvenanceSource().name());
        properties.put("commitSha", state.getCommitSha());
        properties.put(
                "createdAt",
                state.getCreatedAt() == null ? null : state.getCreatedAt().toString());
        properties.put(
                "updatedAt",
                state.getUpdatedAt() == null ? null : state.getUpdatedAt().toString());
        return new GraphNode(
                GraphIds.nodeId(GraphEntityType.ARCHITECTURE_MODEL_ELEMENT, element.getId()),
                element.getId().toString(),
                GraphEntityType.ARCHITECTURE_MODEL_ELEMENT,
                state.getProject().getIdentifier(),
                state.getStableKey(),
                state.getLabel(),
                properties);
    }

    private GraphEdge toFlowEdge(
            ArchitectureModelElementState flowState, Map<String, ArchitectureModelElementState> byStableKey) {
        var source = byStableKey.get(flowState.getFlowSourceStableKey());
        var target = byStableKey.get(flowState.getFlowTargetStableKey());
        if (source == null || target == null) {
            return null;
        }
        var properties = new LinkedHashMap<String, Object>();
        properties.put("flowStableKey", flowState.getStableKey());
        properties.put(
                "flowDirection",
                flowState.getFlowDirection() == null
                        ? ArchitectureFlowDirection.UNIDIRECTIONAL.name()
                        : flowState.getFlowDirection().name());
        properties.put("modelVersion", flowState.getSnapshot().getModelVersion());
        return new GraphEdge(
                "ARCHITECTURE_MODEL_DATA_FLOW:" + flowState.getElement().getId(),
                "DATA_FLOW",
                GraphIds.nodeId(
                        GraphEntityType.ARCHITECTURE_MODEL_ELEMENT,
                        source.getElement().getId()),
                GraphIds.nodeId(
                        GraphEntityType.ARCHITECTURE_MODEL_ELEMENT,
                        target.getElement().getId()),
                GraphEntityType.ARCHITECTURE_MODEL_ELEMENT,
                GraphEntityType.ARCHITECTURE_MODEL_ELEMENT,
                properties);
    }

    private void putIfPresent(Map<String, Object> properties, String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }
}
