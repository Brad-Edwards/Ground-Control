package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.FairCamControlDomain;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlAnalyticsService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FairCamControlAnalyticsServiceTest {

    @Mock
    private ControlEffectivenessAssessmentRepository repository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private FairCamControlAnalyticsService service;

    private Project project;
    private UUID projectId;
    private Control control;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        control = new Control(project, "CTRL-001", "Multi-Factor Authentication", ControlFunction.PREVENTIVE);
        setField(control, "id", UUID.randomUUID());
    }

    private ControlEffectivenessAssessment makeAssessment(
            ControlEffectivenessRating design,
            ControlEffectivenessRating operating,
            FairCamControlDomain domain,
            List<String> supportingTestIds) {
        var a = new ControlEffectivenessAssessment(
                project,
                control,
                "CEA-" + UUID.randomUUID().toString().substring(0, 8),
                design,
                operating,
                LocalDate.parse("2026-04-01"),
                "Auditor");
        setField(a, "id", UUID.randomUUID());
        a.setFairCamControlDomain(domain);
        a.setSupportingTestIds(supportingTestIds);
        return a;
    }

    @Test
    void analyze_returnsMethodologyAttributedEnvelopeWithThreeDimensions() {
        var assessment = makeAssessment(
                ControlEffectivenessRating.EFFECTIVE,
                ControlEffectivenessRating.EFFECTIVE,
                FairCamControlDomain.LOSS_EVENT_CONTROL,
                List.of(UUID.randomUUID().toString()));
        when(repository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        Mockito_eq(projectId), Mockito_anyDate()))
                .thenReturn(List.of(assessment));

        var result = service.analyze(projectId, Instant.parse("2026-05-30T00:00:00Z"), null);

        assertThat(result.analysisKind()).isEqualTo("fair_cam_control_analytics");
        assertThat(result.project()).isEqualTo("ground-control");
        assertThat(result.scale()).isEqualTo("fraction");
        assertThat(result.controls()).hasSize(1);
        var item = result.controls().get(0);
        assertThat(item.fairCamControlDomain()).isEqualTo(FairCamControlDomain.LOSS_EVENT_CONTROL);
        assertThat(item.dimensions().capability().value()).isEqualTo(1.0);
        assertThat(item.dimensions().operationalPerformance().value()).isEqualTo(1.0);
        assertThat(item.dimensions().coverage().value()).isEqualTo(1.0);
        assertThat(item.dimensions().capability().units()).isNotBlank();
        assertThat(item.dimensions().operationalPerformance().units()).isNotBlank();
        assertThat(item.dimensions().coverage().units()).isNotBlank();
        // GC-I017: FAIR-CAM dimensions are reported beside the legacy rating,
        // not absorbed by it.
        assertThat(item.designEffectiveness()).isEqualTo(ControlEffectivenessRating.EFFECTIVE);
    }

    @Test
    void analyze_missingFairCamDomain_emitsLimitation() {
        var assessment = makeAssessment(
                ControlEffectivenessRating.EFFECTIVE,
                ControlEffectivenessRating.PARTIALLY_EFFECTIVE,
                null,
                List.of(UUID.randomUUID().toString()));
        when(repository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        Mockito_eq(projectId), Mockito_anyDate()))
                .thenReturn(List.of(assessment));

        var result = service.analyze(projectId, null, null);

        assertThat(result.controls()).hasSize(1);
        assertThat(result.controls().get(0).limitations())
                .anyMatch(l -> l.contains("FAIR-CAM control_domain not attributed"));
        assertThat(result.counts().byDomain()).containsKey("UNATTRIBUTED");
    }

    @Test
    void analyze_missingSupportingTests_setsCoverageToZeroWithLimitation() {
        var assessment = makeAssessment(
                ControlEffectivenessRating.EFFECTIVE,
                ControlEffectivenessRating.EFFECTIVE,
                FairCamControlDomain.VARIANCE_MANAGEMENT_CONTROL,
                null);
        when(repository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        Mockito_eq(projectId), Mockito_anyDate()))
                .thenReturn(List.of(assessment));

        var result = service.analyze(projectId, null, null);

        var item = result.controls().get(0);
        assertThat(item.dimensions().coverage().value()).isEqualTo(0.0);
        assertThat(item.limitations()).anyMatch(l -> l.contains("supporting ControlTest evidence"));
    }

    @Test
    void analyze_noAssessmentsAtAll_emitsTopLevelLimitation() {
        when(repository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        Mockito_eq(projectId), Mockito_anyDate()))
                .thenReturn(List.of());

        var result = service.analyze(projectId, null, null);

        assertThat(result.controls()).isEmpty();
        assertThat(result.limitations()).anyMatch(l -> l.contains("no ControlEffectivenessAssessment evidence"));
    }

    @Test
    void analyze_filterByControlId_usesProjectScopedRepo() {
        UUID controlId = control.getId();
        var assessment = makeAssessment(
                ControlEffectivenessRating.INEFFECTIVE,
                ControlEffectivenessRating.INEFFECTIVE,
                FairCamControlDomain.DECISION_SUPPORT_CONTROL,
                List.of());
        when(repository.findByProjectIdAndControlIdOrderByAssessedAtDesc(projectId, controlId))
                .thenReturn(List.of(assessment));

        var result = service.analyze(projectId, Instant.parse("2026-12-31T00:00:00Z"), controlId);

        assertThat(result.controls()).hasSize(1);
        assertThat(result.controls().get(0).fairCamControlDomain())
                .isEqualTo(FairCamControlDomain.DECISION_SUPPORT_CONTROL);
        assertThat(result.controls().get(0).dimensions().capability().value()).isEqualTo(0.0);
    }

    @Test
    void analyze_projectNotFound_throws404() {
        UUID unknown = UUID.randomUUID();
        when(projectRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(unknown, null, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void analyze_dimensionsReportedIndependently_neverCollapsedIntoSingleScore() {
        // GC-I017: FAIR-CAM dimensions (capability, coverage, operational
        // performance) are reported independently. Confirm three separate
        // measurements appear and each carries its own derivation.
        var assessment = makeAssessment(
                ControlEffectivenessRating.PARTIALLY_EFFECTIVE,
                ControlEffectivenessRating.EFFECTIVE,
                FairCamControlDomain.LOSS_EVENT_CONTROL,
                List.of("test-1"));
        when(repository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        Mockito_eq(projectId), Mockito_anyDate()))
                .thenReturn(List.of(assessment));

        var result = service.analyze(projectId, null, null);

        var dims = result.controls().get(0).dimensions();
        assertThat(dims.capability().value()).isEqualTo(0.5);
        assertThat(dims.operationalPerformance().value()).isEqualTo(1.0);
        assertThat(dims.coverage().value()).isEqualTo(1.0);
        assertThat(dims.capability().derivation()).isNotBlank();
        assertThat(dims.coverage().derivation()).isNotBlank();
        assertThat(dims.operationalPerformance().derivation()).isNotBlank();
    }

    // Mockito's static helpers shadowed locally so the test reads naturally
    // even when both the matcher and a UUID share a method name.
    private static UUID Mockito_eq(UUID id) {
        return org.mockito.ArgumentMatchers.eq(id);
    }

    private static LocalDate Mockito_anyDate() {
        return org.mockito.ArgumentMatchers.any(LocalDate.class);
    }
}
