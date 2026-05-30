package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskHeatmapResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskHeatmapService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class RiskHeatmapServiceTest {

    @Mock
    private RiskAssessmentResultRepository repository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private RiskHeatmapService service;

    private Project project;
    private UUID projectId;
    private MethodologyProfile nistProfile;
    private MethodologyProfile fairProfile;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);

        nistProfile = new MethodologyProfile(
                project, "NIST", "NIST SP 800-30 Rev. 1", "1", MethodologyFamily.NIST_SP800_30_R1);
        setField(nistProfile, "id", UUID.randomUUID());

        fairProfile = new MethodologyProfile(project, "FAIR", "FAIR", "1", MethodologyFamily.FAIR);
        setField(fairProfile, "id", UUID.randomUUID());
    }

    private RiskAssessmentResult assessment(MethodologyProfile profile, Map<String, Object> computed) {
        var scenario = new RiskScenario(project, "RS-1", "scenario", "T", "M", "A", "E");
        setField(scenario, "id", UUID.randomUUID());
        var r = new RiskAssessmentResult(project, scenario, profile);
        setField(r, "id", UUID.randomUUID());
        if (computed != null) {
            r.setComputedOutputs(computed);
        }
        return r;
    }

    @Test
    void happyPath_plotsCellByOrdinalAndCarriesAttribution() {
        var row = assessment(nistProfile, Map.of("overall_likelihood", "HIGH", "impact_level", "MODERATE"));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(row));

        RiskHeatmapResult result = service.buildHeatmap(projectId, Instant.parse("2026-05-30T00:00:00Z"), null);

        assertThat(result.analysisKind()).isEqualTo("risk_heatmap");
        assertThat(result.derivationMethod()).isEqualTo("qualitative-likelihood-impact-heatmap-v1");
        assertThat(result.scale()).isEqualTo("ordinal");
        assertThat(result.cells()).hasSize(1);
        RiskHeatmapResult.HeatmapCell cell = result.cells().get(0);
        assertThat(cell.likelihoodBand()).isEqualTo("HIGH");
        assertThat(cell.impactBand()).isEqualTo("MODERATE");
        // HIGH ordinal = 4, MODERATE ordinal = 3 (1-based)
        assertThat(cell.likelihoodOrdinal()).isEqualTo(4);
        assertThat(cell.impactOrdinal()).isEqualTo(3);
        assertThat(cell.count()).isEqualTo(1);
        assertThat(cell.riskAssessmentResultIds()).containsExactly(row.getId());
        assertThat(result.counts().assessmentsPlotted()).isEqualTo(1);
        assertThat(result.counts().byMethodologyFamily()).containsEntry("NIST_SP800_30_R1", 1);
    }

    @Test
    void fairRow_isExcludedAndEmitsLimitation() {
        var fair = assessment(fairProfile, Map.of("ale_p90", "12345.0"));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(fair));

        RiskHeatmapResult result = service.buildHeatmap(projectId, Instant.parse("2026-05-30T00:00:00Z"), null);

        assertThat(result.cells()).isEmpty();
        assertThat(result.counts().assessmentsPlotted()).isZero();
        assertThat(result.counts().assessmentsIncompatible()).isEqualTo(1);
        assertThat(result.limitations()).anyMatch(s -> s.contains("FAIR") && s.contains("quantitative"));
    }

    @Test
    void fallsBackToInputFactors_whenComputedOutputsAreMissing() {
        var row = assessment(nistProfile, null);
        row.setInputFactors(Map.of("likelihood_overall", "VERY_HIGH", "impact_level", "VERY_LOW"));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(row));

        RiskHeatmapResult result = service.buildHeatmap(projectId, Instant.parse("2026-05-30T00:00:00Z"), null);

        assertThat(result.cells()).hasSize(1);
        assertThat(result.cells().get(0).likelihoodBand()).isEqualTo("VERY_HIGH");
        assertThat(result.cells().get(0).impactBand()).isEqualTo("VERY_LOW");
    }

    @Test
    void unparseableBand_isExcluded() {
        var row = assessment(nistProfile, Map.of("overall_likelihood", "GIGA", "impact_level", "HIGH"));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(row));

        RiskHeatmapResult result = service.buildHeatmap(projectId, Instant.parse("2026-05-30T00:00:00Z"), null);

        assertThat(result.cells()).isEmpty();
        assertThat(result.counts().assessmentsIncompatible()).isEqualTo(1);
    }

    @Test
    void profileRestriction_filtersAndEmitsLimitationWhenIncompatible() {
        var row = assessment(fairProfile, Map.of("ale_p90", "0"));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(row));

        RiskHeatmapResult result =
                service.buildHeatmap(projectId, Instant.parse("2026-05-30T00:00:00Z"), fairProfile.getId());

        assertThat(result.cells()).isEmpty();
        assertThat(result.methodologyProfileId()).isEqualTo(fairProfile.getId());
        assertThat(result.methodologyFamily()).isEqualTo("FAIR");
        assertThat(result.limitations())
                .anyMatch(s -> s.contains("requested methodology profile does not produce ordinal"));
    }

    @Test
    void projectNotFound_throws() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildHeatmap(projectId, Instant.now(), null))
                .isInstanceOf(NotFoundException.class);
    }
}
