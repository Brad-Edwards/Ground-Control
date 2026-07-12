package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.service.RiskControlMappingGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for RiskControlMappingGraphProjectionContributor (GC-T003). */
@ExtendWith(MockitoExtension.class)
class RiskControlMappingGraphProjectionContributorTest {

    @Mock
    private RiskControlMappingRepository mappingRepository;

    @Mock
    private ScopedControlImplementationRepository sciRepository;

    @InjectMocks
    private RiskControlMappingGraphProjectionContributor contributor;

    private Project project;
    private UUID projectId;
    private Control control;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);

        control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        setField(control, "id", UUID.randomUUID());
    }

    @Test
    void contributeNodes_includesMappingNode() {
        var scenario = new RiskScenario(project, "RS-001", "Phishing", "A", "B", "C", "D");
        setField(scenario, "id", UUID.randomUUID());

        var mapping = RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
        var mappingId = UUID.randomUUID();
        setField(mapping, "id", mappingId);
        mapping.setMappingObjective("Prevent attacks");

        when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));
        when(sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes).hasSize(1);
        var node = nodes.get(0);
        assertThat(node.entityType()).isEqualTo(GraphEntityType.RISK_CONTROL_MAPPING);
        assertThat(node.properties()).containsEntry("controlRole", "PREVENTIVE");
        assertThat(node.properties()).containsEntry("mappingObjective", "Prevent attacks");
    }

    @Test
    void contributeNodes_includesSciNode() {
        var sci = new ScopedControlImplementation(project, "SCI-001", control, "Email Gateway");
        var sciId = UUID.randomUUID();
        setField(sci, "id", sciId);

        when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());
        when(sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(sci));

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes).hasSize(1);
        var node = nodes.get(0);
        assertThat(node.entityType()).isEqualTo(GraphEntityType.SCOPED_CONTROL_IMPLEMENTATION);
        assertThat(node.uid()).isEqualTo("SCI-001");
        assertThat(node.properties()).containsEntry("name", "Email Gateway");
    }

    @Test
    void contributeEdges_mapsControlToScenario() {
        var scenario = new RiskScenario(project, "RS-001", "Phishing", "A", "B", "C", "D");
        var scenarioId = UUID.randomUUID();
        setField(scenario, "id", scenarioId);

        var mapping = RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
        var mappingId = UUID.randomUUID();
        setField(mapping, "id", mappingId);

        when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));
        when(sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges).hasSize(2); // control edge + scenario edge
        var edgeTypes = edges.stream().map(GraphEdge::edgeType).toList();
        assertThat(edgeTypes).contains("MAPS_CONTROL", "MAPS_SCENARIO");

        var controlEdge = edges.stream()
                .filter(e -> e.edgeType().equals("MAPS_CONTROL"))
                .findFirst()
                .orElseThrow();
        assertThat(controlEdge.sourceId()).isEqualTo(GraphIds.nodeId(GraphEntityType.RISK_CONTROL_MAPPING, mappingId));
        assertThat(controlEdge.targetId()).isEqualTo(GraphIds.nodeId(GraphEntityType.CONTROL, control.getId()));
    }

    @Test
    void contributeEdges_mapsScopedImplToScenario() {
        var scenario = new RiskScenario(project, "RS-001", "Phishing", "A", "B", "C", "D");
        var scenarioId = UUID.randomUUID();
        setField(scenario, "id", scenarioId);

        var sci = new ScopedControlImplementation(project, "SCI-001", control, "Email Gateway");
        var sciId = UUID.randomUUID();
        setField(sci, "id", sciId);

        var mapping = RiskControlMapping.forScopedScenario(project, sci, scenario, MappingControlRole.DETECTIVE);
        var mappingId = UUID.randomUUID();
        setField(mapping, "id", mappingId);

        when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));
        when(sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(sci));

        var edges = contributor.contributeEdges(projectId);

        var edgeTypes = edges.stream().map(GraphEdge::edgeType).toList();
        assertThat(edgeTypes).contains("MAPS_SCOPED_IMPL", "MAPS_SCENARIO", "SCOPED_IMPL_OF");
    }

    @Test
    void contributeEdges_includesAssetContextEdge() {
        var scenario = new RiskScenario(project, "RS-001", "Phishing", "A", "B", "C", "D");
        setField(scenario, "id", UUID.randomUUID());

        var asset = new OperationalAsset(project, "ASSET-001", "Web Server");
        var assetId = UUID.randomUUID();
        setField(asset, "id", assetId);

        var mapping = RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
        setField(mapping, "id", UUID.randomUUID());
        mapping.setOperationalAsset(asset);

        when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));
        when(sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges.stream().anyMatch(e -> e.edgeType().equals("IN_ASSET_CONTEXT")))
                .isTrue();
    }

    @Test
    void contributeEdges_includesObservationEdge() {
        var scenario = new RiskScenario(project, "RS-001", "Phishing", "A", "B", "C", "D");
        setField(scenario, "id", UUID.randomUUID());

        var asset = new OperationalAsset(project, "ASSET-001", "Web Server");
        setField(asset, "id", UUID.randomUUID());

        var observation = new Observation(
                asset,
                ObservationCategory.CONFIGURATION,
                "tls_enabled",
                "true",
                "scanner",
                Instant.parse("2026-05-01T00:00:00Z"));
        setField(observation, "id", UUID.randomUUID());

        var mapping = RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
        setField(mapping, "id", UUID.randomUUID());
        mapping.addObservation(observation);

        when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));
        when(sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges.stream().anyMatch(e -> e.edgeType().equals("HAS_OBSERVATION")))
                .isTrue();
    }

    @Test
    void contributeEdges_sciWithAsset_includesScopedToAssetEdge() {
        var sci = new ScopedControlImplementation(project, "SCI-001", control, "Email Gateway");
        setField(sci, "id", UUID.randomUUID());

        var asset = new OperationalAsset(project, "ASSET-001", "Web Server");
        setField(asset, "id", UUID.randomUUID());
        sci.setOperationalAsset(asset);

        when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());
        when(sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(sci));

        var edges = contributor.contributeEdges(projectId);

        var edgeTypes = edges.stream().map(GraphEdge::edgeType).toList();
        assertThat(edgeTypes).contains("SCOPED_IMPL_OF", "SCOPED_TO_ASSET");
    }

    @Test
    void contributeNodes_mappingWithoutObjective_omitsObjectiveProperty() {
        var scenario = new RiskScenario(project, "RS-001", "Phishing", "A", "B", "C", "D");
        setField(scenario, "id", UUID.randomUUID());

        var mapping = RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.DETECTIVE);
        setField(mapping, "id", UUID.randomUUID());

        when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));
        when(sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).properties()).doesNotContainKey("mappingObjective");
    }

    @Test
    void contributeEdges_mapsThreatModelEndpoint() {
        var threatModel = new com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel(
                project, "TM-001", "SQL Injection", "Attacker", "Inject SQL", "Data exfiltration");
        var threatModelId = UUID.randomUUID();
        setField(threatModel, "id", threatModelId);

        var mapping = RiskControlMapping.forControlThreat(project, control, threatModel, MappingControlRole.PREVENTIVE);
        setField(mapping, "id", UUID.randomUUID());

        when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));
        when(sciRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());

        var edges = contributor.contributeEdges(projectId);

        var edgeTypes = edges.stream().map(GraphEdge::edgeType).toList();
        assertThat(edgeTypes).contains("MAPS_CONTROL", "MAPS_THREAT_MODEL");
    }
}
