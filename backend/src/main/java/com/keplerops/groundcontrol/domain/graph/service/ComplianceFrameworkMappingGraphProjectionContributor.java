package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.compliance.repository.ComplianceFrameworkMappingRepository;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Projects {@link com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping}
 * rows into the graph for GC-I002 / GC-I005 / GC-I007.
 *
 * <p>Edges emitted:
 * <ul>
 *   <li>ComplianceFrameworkMapping -> Requirement (MAPS_REQUIREMENT) when the
 *       mapping is requirement-side.
 *   <li>ComplianceFrameworkMapping -> Control (MAPS_CONTROL_TO_FRAMEWORK) when
 *       the mapping is control-side.
 * </ul>
 *
 * <p>The framework element itself is not a graph node; it lives as a
 * (framework, element) tuple on the mapping node so cross-framework gap
 * queries can group mappings by element without inflating the node count.
 */
@Component
public class ComplianceFrameworkMappingGraphProjectionContributor implements GraphProjectionContributor {

    private static final String EDGE_MAPS_REQUIREMENT = "MAPS_REQUIREMENT";
    private static final String EDGE_MAPS_CONTROL_TO_FRAMEWORK = "MAPS_CONTROL_TO_FRAMEWORK";

    private final ComplianceFrameworkMappingRepository repository;

    public ComplianceFrameworkMappingGraphProjectionContributor(ComplianceFrameworkMappingRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        var nodes = new ArrayList<GraphNode>();
        for (var mapping : repository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("framework", mapping.getFramework().name());
            props.put("frameworkElement", mapping.getFrameworkElement());
            props.put("coverageLevel", mapping.getCoverageLevel().name());
            if (mapping.getFrameworkIdentifier() != null) {
                props.put("frameworkIdentifier", mapping.getFrameworkIdentifier());
            }
            if (mapping.getFrameworkVersion() != null) {
                props.put("frameworkVersion", mapping.getFrameworkVersion());
            }
            String label = "CFM:" + mapping.getFramework().name() + ":" + mapping.getFrameworkElement();
            nodes.add(new GraphNode(
                    GraphIds.nodeId(GraphEntityType.COMPLIANCE_FRAMEWORK_MAPPING, mapping.getId()),
                    mapping.getId().toString(),
                    GraphEntityType.COMPLIANCE_FRAMEWORK_MAPPING,
                    mapping.getProject().getIdentifier(),
                    mapping.getId().toString(),
                    label,
                    props));
        }
        return nodes;
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        var edges = new ArrayList<GraphEdge>();
        for (var mapping : repository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            var mappingNodeId = GraphIds.nodeId(GraphEntityType.COMPLIANCE_FRAMEWORK_MAPPING, mapping.getId());
            if (mapping.getRequirement() != null) {
                edges.add(new GraphEdge(
                        mapping.getId() + ":requirement",
                        EDGE_MAPS_REQUIREMENT,
                        mappingNodeId,
                        GraphIds.nodeId(
                                GraphEntityType.REQUIREMENT,
                                mapping.getRequirement().getId()),
                        GraphEntityType.COMPLIANCE_FRAMEWORK_MAPPING,
                        GraphEntityType.REQUIREMENT,
                        Map.of()));
            } else if (mapping.getControl() != null) {
                edges.add(new GraphEdge(
                        mapping.getId() + ":control",
                        EDGE_MAPS_CONTROL_TO_FRAMEWORK,
                        mappingNodeId,
                        GraphIds.nodeId(
                                GraphEntityType.CONTROL, mapping.getControl().getId()),
                        GraphEntityType.COMPLIANCE_FRAMEWORK_MAPPING,
                        GraphEntityType.CONTROL,
                        Map.of()));
            }
        }
        return edges;
    }
}
