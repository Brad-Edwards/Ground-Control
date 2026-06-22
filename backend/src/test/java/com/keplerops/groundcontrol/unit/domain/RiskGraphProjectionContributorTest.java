package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.service.RiskGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenarioLink;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
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

    @Mock
    private RiskRegisterRecordRepository riskRegisterRecordRepository;

    @Mock
    private RiskAssessmentResultRepository riskAssessmentResultRepository;

    @Mock
    private TreatmentPlanRepository treatmentPlanRepository;

    @Mock
    private MethodologyProfileRepository methodologyProfileRepository;

    @InjectMocks
    private RiskGraphProjectionContributor contributor;

    @Test
    void contributesRiskNodesAndTypedEdges() {
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

        var riskRecord = new RiskRegisterRecord(project, "RR-1", "Primary record");
        setField(riskRecord, "id", UUID.randomUUID());
        riskRecord.replaceRiskScenarios(List.of(scenario));

        var profile = new MethodologyProfile(
                project, "FAIR_V3_0", "Open FAIR", "O-RT 3.0.1 / O-RA 2.0.1", MethodologyFamily.FAIR);
        setField(profile, "id", UUID.randomUUID());

        var observationAsset = new OperationalAsset(project, "ASSET-1", "Gateway");
        setField(observationAsset, "id", UUID.randomUUID());
        observationAsset.setAssetType(AssetType.SERVICE);
        var observation = new Observation(
                observationAsset,
                ObservationCategory.EXPOSURE,
                "public_access",
                "true",
                "scanner",
                Instant.parse("2026-04-02T00:00:00Z"));
        setField(observation, "id", UUID.randomUUID());

        var assessment = new RiskAssessmentResult(project, scenario, profile);
        setField(assessment, "id", UUID.randomUUID());
        assessment.setAssessmentAt(Instant.parse("2026-04-02T12:00:00Z"));
        assessment.setConfidence("HIGH");
        assessment.replaceObservations(List.of(observation));

        var treatmentPlan =
                new TreatmentPlan(project, "TP-1", "Mitigate gateway", riskRecord, TreatmentStrategy.MITIGATE);
        setField(treatmentPlan, "id", UUID.randomUUID());

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
        when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(riskRecord));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));
        when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(treatmentPlan));
        when(methodologyProfileRepository.findByProjectIdOrderByNameAscVersionDesc(projectId))
                .thenReturn(List.of(profile));

        var nodes = contributor.contributeNodes(projectId);
        var edges = contributor.contributeEdges(projectId);

        assertThat(nodes).hasSize(5);
        assertThat(nodes)
                .extracting(node -> node.entityType().name())
                .containsExactlyInAnyOrder(
                        "RISK_SCENARIO",
                        "RISK_REGISTER_RECORD",
                        "RISK_ASSESSMENT_RESULT",
                        "TREATMENT_PLAN",
                        "METHODOLOGY_PROFILE");
        assertThat(nodes).noneMatch(node -> "RS-ARCH".equals(node.uid()));
        assertThat(edges)
                .extracting(GraphEdge::edgeType)
                .containsExactlyInAnyOrder(
                        "AFFECTS", "TRACKS", "ASSESSES", "USES_METHOD", "USED_OBSERVATION", "TREATS");
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
        when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());
        when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).targetEntityType()).isEqualTo(GraphEntityType.EVIDENCE_ARTIFACT);
        assertThat(edges.get(0).targetId()).isEqualTo(GraphIds.nodeId(GraphEntityType.EVIDENCE_ARTIFACT, evidenceId));
    }
}
