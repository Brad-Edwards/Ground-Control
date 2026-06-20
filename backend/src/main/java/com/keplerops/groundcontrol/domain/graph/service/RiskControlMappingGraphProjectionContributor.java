package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Projects {@link com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping} and
 * {@link com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation} rows
 * into the graph for GC-T003.
 *
 * <p>Edges emitted:
 * <ul>
 *   <li>RiskControlMapping → Control (MAPS_CONTROL or MAPS_SCOPED_IMPL)
 *   <li>RiskControlMapping → RiskScenario or RiskRegisterRecord (MAPS_SCENARIO / MAPS_RECORD)
 *   <li>RiskControlMapping → OperationalAsset (IN_ASSET_CONTEXT) when set
 *   <li>RiskControlMapping → Observation (HAS_OBSERVATION) for C8 provenance
 *   <li>ScopedControlImplementation → Control (SCOPED_IMPL_OF)
 *   <li>ScopedControlImplementation → OperationalAsset (SCOPED_TO_ASSET) when set
 * </ul>
 */
@Component
public class RiskControlMappingGraphProjectionContributor implements GraphProjectionContributor {

    private static final String EDGE_MAPS_CONTROL = "MAPS_CONTROL";
    private static final String EDGE_MAPS_SCOPED_IMPL = "MAPS_SCOPED_IMPL";
    private static final String EDGE_MAPS_SCENARIO = "MAPS_SCENARIO";
    private static final String EDGE_MAPS_RECORD = "MAPS_RECORD";
    private static final String EDGE_MAPS_THREAT_MODEL = "MAPS_THREAT_MODEL";
    private static final String EDGE_IN_ASSET_CONTEXT = "IN_ASSET_CONTEXT";
    private static final String EDGE_HAS_OBSERVATION = "HAS_OBSERVATION";
    private static final String EDGE_SCOPED_IMPL_OF = "SCOPED_IMPL_OF";
    private static final String EDGE_SCOPED_TO_ASSET = "SCOPED_TO_ASSET";

    private final RiskControlMappingRepository mappingRepository;
    private final ScopedControlImplementationRepository sciRepository;

    public RiskControlMappingGraphProjectionContributor(
            RiskControlMappingRepository mappingRepository, ScopedControlImplementationRepository sciRepository) {
        this.mappingRepository = mappingRepository;
        this.sciRepository = sciRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        var nodes = new ArrayList<GraphNode>();

        // RiskControlMapping nodes
        for (var mapping : mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("controlRole", mapping.getControlRole().name());
            if (mapping.getMappingObjective() != null) {
                props.put("mappingObjective", mapping.getMappingObjective());
            }
            nodes.add(new GraphNode(
                    GraphIds.nodeId(GraphEntityType.RISK_CONTROL_MAPPING, mapping.getId()),
                    mapping.getId().toString(),
                    GraphEntityType.RISK_CONTROL_MAPPING,
                    mapping.getProject().getIdentifier(),
                    mapping.getId().toString(),
                    "RCM:" + mapping.getControlRole().name(),
                    props));
        }

        // ScopedControlImplementation nodes
        for (var sci : sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("uid", sci.getUid());
            props.put("name", sci.getName());
            props.put("controlUid", sci.getControl().getUid());
            nodes.add(new GraphNode(
                    GraphIds.nodeId(GraphEntityType.SCOPED_CONTROL_IMPLEMENTATION, sci.getId()),
                    sci.getId().toString(),
                    GraphEntityType.SCOPED_CONTROL_IMPLEMENTATION,
                    sci.getProject().getIdentifier(),
                    sci.getUid(),
                    sci.getName(),
                    props));
        }

        return nodes;
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        var edges = new ArrayList<GraphEdge>();

        for (var mapping : mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            var mappingNodeId = GraphIds.nodeId(GraphEntityType.RISK_CONTROL_MAPPING, mapping.getId());

            // Control-side edge
            if (mapping.getControl() != null) {
                edges.add(new GraphEdge(
                        mapping.getId() + ":control",
                        EDGE_MAPS_CONTROL,
                        mappingNodeId,
                        GraphIds.nodeId(
                                GraphEntityType.CONTROL, mapping.getControl().getId()),
                        GraphEntityType.RISK_CONTROL_MAPPING,
                        GraphEntityType.CONTROL,
                        Map.of()));
            } else if (mapping.getScopedImplementation() != null) {
                edges.add(new GraphEdge(
                        mapping.getId() + ":scoped",
                        EDGE_MAPS_SCOPED_IMPL,
                        mappingNodeId,
                        GraphIds.nodeId(
                                GraphEntityType.SCOPED_CONTROL_IMPLEMENTATION,
                                mapping.getScopedImplementation().getId()),
                        GraphEntityType.RISK_CONTROL_MAPPING,
                        GraphEntityType.SCOPED_CONTROL_IMPLEMENTATION,
                        Map.of()));
            }

            // Analysis-side edge
            if (mapping.getRiskScenario() != null) {
                edges.add(new GraphEdge(
                        mapping.getId() + ":scenario",
                        EDGE_MAPS_SCENARIO,
                        mappingNodeId,
                        GraphIds.nodeId(
                                GraphEntityType.RISK_SCENARIO,
                                mapping.getRiskScenario().getId()),
                        GraphEntityType.RISK_CONTROL_MAPPING,
                        GraphEntityType.RISK_SCENARIO,
                        Map.of()));
            } else if (mapping.getRiskRegisterRecord() != null) {
                edges.add(new GraphEdge(
                        mapping.getId() + ":record",
                        EDGE_MAPS_RECORD,
                        mappingNodeId,
                        GraphIds.nodeId(
                                GraphEntityType.RISK_REGISTER_RECORD,
                                mapping.getRiskRegisterRecord().getId()),
                        GraphEntityType.RISK_CONTROL_MAPPING,
                        GraphEntityType.RISK_REGISTER_RECORD,
                        Map.of()));
            } else if (mapping.getThreatModel() != null) {
                edges.add(new GraphEdge(
                        mapping.getId() + ":threat",
                        EDGE_MAPS_THREAT_MODEL,
                        mappingNodeId,
                        GraphIds.nodeId(
                                GraphEntityType.THREAT_MODEL,
                                mapping.getThreatModel().getId()),
                        GraphEntityType.RISK_CONTROL_MAPPING,
                        GraphEntityType.THREAT_MODEL,
                        Map.of()));
            }

            // Asset context edge (C2)
            if (mapping.getOperationalAsset() != null) {
                edges.add(new GraphEdge(
                        mapping.getId() + ":asset",
                        EDGE_IN_ASSET_CONTEXT,
                        mappingNodeId,
                        GraphIds.nodeId(
                                GraphEntityType.OPERATIONAL_ASSET,
                                mapping.getOperationalAsset().getId()),
                        GraphEntityType.RISK_CONTROL_MAPPING,
                        GraphEntityType.OPERATIONAL_ASSET,
                        Map.of()));
            }

            // Observation edges (C8 provenance)
            for (var obs : mapping.getObservations()) {
                edges.add(new GraphEdge(
                        mapping.getId() + ":obs:" + obs.getId(),
                        EDGE_HAS_OBSERVATION,
                        mappingNodeId,
                        GraphIds.nodeId(GraphEntityType.OBSERVATION, obs.getId()),
                        GraphEntityType.RISK_CONTROL_MAPPING,
                        GraphEntityType.OBSERVATION,
                        Map.of()));
            }
        }

        // ScopedControlImplementation edges
        for (var sci : sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            var sciNodeId = GraphIds.nodeId(GraphEntityType.SCOPED_CONTROL_IMPLEMENTATION, sci.getId());

            edges.add(new GraphEdge(
                    sci.getId() + ":control",
                    EDGE_SCOPED_IMPL_OF,
                    sciNodeId,
                    GraphIds.nodeId(GraphEntityType.CONTROL, sci.getControl().getId()),
                    GraphEntityType.SCOPED_CONTROL_IMPLEMENTATION,
                    GraphEntityType.CONTROL,
                    Map.of()));

            if (sci.getOperationalAsset() != null) {
                edges.add(new GraphEdge(
                        sci.getId() + ":asset",
                        EDGE_SCOPED_TO_ASSET,
                        sciNodeId,
                        GraphIds.nodeId(
                                GraphEntityType.OPERATIONAL_ASSET,
                                sci.getOperationalAsset().getId()),
                        GraphEntityType.SCOPED_CONTROL_IMPLEMENTATION,
                        GraphEntityType.OPERATIONAL_ASSET,
                        Map.of()));
            }
        }

        return edges;
    }
}
