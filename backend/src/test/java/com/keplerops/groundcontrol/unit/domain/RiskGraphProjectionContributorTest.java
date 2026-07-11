package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.service.RiskGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenarioLink;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskGraphProjectionContributorTest {

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private RiskScenarioLinkRepository riskScenarioLinkRepository;

    @InjectMocks
    private RiskGraphProjectionContributor contributor;

    @Test
    void contributesRiskScenarioNodesAndTypedEdges() {
        var project = new Project("ground-control", "Ground Control");
        var projectId = UUID.randomUUID();
        setField(project, "id", projectId);

        var scenario = new RiskScenario(project, "RS-1", "Scenario", "Actor", "Exploit", "Gateway", "Service outage");
        setField(scenario, "id", UUID.randomUUID());
        scenario.setTimeHorizon("12 months");
        scenario.setCreatedBy("analyst");
        scenario.transitionStatus(RiskScenarioStatus.ACTIVE);

        var archived = new RiskScenario(project, "RS-ARCH", "Archived", "Actor", "Exploit", "Legacy", "Old");
        setField(archived, "id", UUID.randomUUID());
        archived.setTimeHorizon("6 months");
        archived.transitionStatus(RiskScenarioStatus.ARCHIVED);

        var observationAsset = new OperationalAsset(project, "ASSET-1", "Gateway");
        setField(observationAsset, "id", UUID.randomUUID());
        observationAsset.setAssetType(AssetType.SERVICE);

        var internalLink = new RiskScenarioLink(
                scenario,
                RiskScenarioLinkTargetType.ASSET,
                observationAsset.getId(),
                null,
                RiskScenarioLinkType.AFFECTS);
        setField(internalLink, "id", UUID.randomUUID());

        var externalLink = new RiskScenarioLink(
                scenario, RiskScenarioLinkTargetType.EXTERNAL, null, "EXT-1", RiskScenarioLinkType.ASSOCIATED);
        setField(externalLink, "id", UUID.randomUUID());

        when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(scenario, archived));
        when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of(internalLink, externalLink));

        var nodes = contributor.contributeNodes(projectId);
        var edges = contributor.contributeEdges(projectId);

        assertThat(nodes).hasSize(1);
        assertThat(nodes).extracting(node -> node.entityType().name()).containsExactly("RISK_SCENARIO");
        assertThat(nodes).noneMatch(node -> "RS-ARCH".equals(node.uid()));
        assertThat(edges).extracting(GraphEdge::edgeType).containsExactly("AFFECTS");
        assertThat(edges)
                .filteredOn(edge -> "AFFECTS".equals(edge.edgeType()))
                .singleElement()
                .extracting(GraphEdge::targetId)
                .isEqualTo(GraphIds.nodeId(GraphEntityType.OPERATIONAL_ASSET, observationAsset.getId()));
    }

    @Test
    void emitsEvidenceArtifactEdgeForEvidenceLink() {
        var project = new Project("ground-control", "Ground Control");
        var projectId = UUID.randomUUID();
        setField(project, "id", projectId);

        var scenario = new RiskScenario(project, "RS-1", "Scenario", "Actor", "Exploit", "Gateway", "Service outage");
        setField(scenario, "id", UUID.randomUUID());
        scenario.setTimeHorizon("12 months");
        scenario.setCreatedBy("analyst");
        scenario.transitionStatus(RiskScenarioStatus.ACTIVE);

        var evidenceId = UUID.randomUUID();
        var evidenceLink = new RiskScenarioLink(
                scenario, RiskScenarioLinkTargetType.EVIDENCE, evidenceId, null, RiskScenarioLinkType.ASSOCIATED);
        setField(evidenceLink, "id", UUID.randomUUID());

        when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of(evidenceLink));

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).targetEntityType()).isEqualTo(GraphEntityType.EVIDENCE_ARTIFACT);
        assertThat(edges.get(0).targetId()).isEqualTo(GraphIds.nodeId(GraphEntityType.EVIDENCE_ARTIFACT, evidenceId));
    }

    @Test
    void retiredTargetTypesProduceNoEdge() {
        // ADR-089: RISK_REGISTER_RECORD, RISK_ASSESSMENT_RESULT, TREATMENT_PLAN, and
        // METHODOLOGY_PROFILE are retired target types with no backing graph node.
        var project = new Project("ground-control", "Ground Control");
        var projectId = UUID.randomUUID();
        setField(project, "id", projectId);

        var scenario = new RiskScenario(project, "RS-1", "Scenario", "Actor", "Exploit", "Gateway", "Service outage");
        setField(scenario, "id", UUID.randomUUID());

        var retiredLink = new RiskScenarioLink(
                scenario,
                RiskScenarioLinkTargetType.RISK_REGISTER_RECORD,
                UUID.randomUUID(),
                null,
                RiskScenarioLinkType.ASSOCIATED);
        setField(retiredLink, "id", UUID.randomUUID());

        when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of(retiredLink));

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges).isEmpty();
    }
}
