package com.keplerops.groundcontrol.unit.domain.riskcontrol;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskcontrol.model.MappingEvidenceRef;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlMappingFeedService;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for C7 (effectiveness feed) and C8 (observation/evidence provenance) in
 * RiskControlMappingFeedService.
 */
@ExtendWith(MockitoExtension.class)
class RiskControlMappingFeedServiceTest {

    @Mock
    private RiskControlMappingRepository mappingRepository;

    @Mock
    private RiskAssessmentResultRepository assessmentRepository;

    @Mock
    private ControlEffectivenessAssessmentRepository effectivenessRepository;

    @InjectMocks
    private RiskControlMappingFeedService service;

    private UUID projectId;
    private Project project;
    private Control control;
    private UUID controlId;
    private RiskScenario scenario;
    private UUID scenarioId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = new Project("test", "Test");
        setField(project, "id", projectId);

        control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        controlId = UUID.randomUUID();
        setField(control, "id", controlId);

        scenario =
                new RiskScenario(project, "RS-001", "Phishing", "Attacker", "Phishing email", "Users", "Data breach");
        scenarioId = UUID.randomUUID();
        setField(scenario, "id", scenarioId);
    }

    @Test
    void feedForAssessment_C7_returnsEffectivenessInputForMappedControl() {
        var assessmentResultId = UUID.randomUUID();
        var result = makeAssessmentResult(assessmentResultId);

        var mapping = RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
        var mappingId = UUID.randomUUID();
        setField(mapping, "id", mappingId);
        setField(mapping, "createdAt", Instant.now());

        var effectAssessment = new ControlEffectivenessAssessment(
                project,
                control,
                "CEA-001",
                ControlEffectivenessRating.EFFECTIVE,
                ControlEffectivenessRating.PARTIALLY_EFFECTIVE,
                LocalDate.of(2026, 5, 1),
                "auditor@test.com");
        var effectAssessmentId = UUID.randomUUID();
        setField(effectAssessment, "id", effectAssessmentId);

        when(assessmentRepository.findByIdAndProjectId(assessmentResultId, projectId))
                .thenReturn(Optional.of(result));
        when(mappingRepository.findByProjectIdAndRiskScenarioId(projectId, scenarioId))
                .thenReturn(List.of(mapping));
        when(effectivenessRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        eq(projectId), any()))
                .thenReturn(List.of(effectAssessment));

        var feed = service.feedForAssessment(projectId, assessmentResultId);

        assertThat(feed.effectivenessInputs()).hasSize(1);
        var input = feed.effectivenessInputs().get(0);
        assertThat(input.controlId()).isEqualTo(controlId);
        assertThat(input.operatingEffectiveness()).isEqualTo("PARTIALLY_EFFECTIVE");
        assertThat(input.designEffectiveness()).isEqualTo("EFFECTIVE");
    }

    @Test
    void feedForAssessment_returnsEmptyFeedWhenNoMappings() {
        var assessmentResultId = UUID.randomUUID();
        var result = makeAssessmentResult(assessmentResultId);

        when(assessmentRepository.findByIdAndProjectId(assessmentResultId, projectId))
                .thenReturn(Optional.of(result));
        when(mappingRepository.findByProjectIdAndRiskScenarioId(projectId, scenarioId))
                .thenReturn(List.of());
        when(effectivenessRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        eq(projectId), any()))
                .thenReturn(List.of());

        var feed = service.feedForAssessment(projectId, assessmentResultId);

        assertThat(feed.effectivenessInputs()).isEmpty();
        assertThat(feed.observationInputs()).isEmpty();
        assertThat(feed.evidenceRefs()).isEmpty();
    }

    @Test
    void feedForAssessment_C8_returnsObservationAndEvidenceFromMapping() {
        var assessmentResultId = UUID.randomUUID();
        var result = makeAssessmentResult(assessmentResultId);

        var mapping = RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
        var mappingId = UUID.randomUUID();
        setField(mapping, "id", mappingId);
        setField(mapping, "createdAt", Instant.now());

        // C8: attach an observation and an evidence ref
        var asset = new OperationalAsset(project, "ASSET-001", "Web Server");
        setField(asset, "id", UUID.randomUUID());
        var obs = new Observation(
                asset, ObservationCategory.CONFIGURATION, "log_retention", "90 days", "manual", Instant.now());
        setField(obs, "id", UUID.randomUUID());
        mapping.addObservation(obs);
        mapping.addEvidenceRef(new MappingEvidenceRef("evidence://policy-doc-001", "Policy document"));

        when(assessmentRepository.findByIdAndProjectId(assessmentResultId, projectId))
                .thenReturn(Optional.of(result));
        when(mappingRepository.findByProjectIdAndRiskScenarioId(projectId, scenarioId))
                .thenReturn(List.of(mapping));
        when(effectivenessRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        eq(projectId), any()))
                .thenReturn(List.of());

        var feed = service.feedForAssessment(projectId, assessmentResultId);

        assertThat(feed.observationInputs()).hasSize(1);
        assertThat(feed.observationInputs().get(0).observationKey()).isEqualTo("log_retention");
        assertThat(feed.observationInputs().get(0).observationValue()).isEqualTo("90 days");

        assertThat(feed.evidenceRefs()).hasSize(1);
        assertThat(feed.evidenceRefs().get(0).getEvidenceRef()).isEqualTo("evidence://policy-doc-001");
    }

    private RiskAssessmentResult makeAssessmentResult(UUID id) {
        // Use reflection to build a RiskAssessmentResult with required fields
        // RiskAssessmentResult constructor takes (project, riskScenario, methodologyProfile)
        // but we'll build it minimally via reflection since protected constructor
        var mp = new com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile(
                project,
                "fair-v1",
                "FAIR",
                "1.0",
                com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily.FAIR);
        setField(mp, "id", UUID.randomUUID());

        var result = new RiskAssessmentResult(project, scenario, mp);
        setField(result, "id", id);
        setField(result, "createdAt", Instant.now());
        return result;
    }
}
