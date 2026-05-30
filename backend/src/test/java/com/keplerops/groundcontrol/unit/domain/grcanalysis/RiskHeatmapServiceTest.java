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
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
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

    @Mock
    private MethodologyProfileRepository methodologyProfileRepository;

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

    /**
     * Adversarial-review finding #2: a caller restricted to a NIST profile must NOT
     * receive a FAIR-incompatibility limitation just because the project has FAIR
     * rows the caller never asked about. The profile filter has to run BEFORE the
     * byFamily accumulation that drives FAIR_INCOMPATIBILITY_LIMITATION.
     */
    @Test
    void profileRestriction_doesNotEmitFairLimitationForFilteredOutFairRows() {
        var nistRow = assessment(nistProfile, Map.of("overall_likelihood", "HIGH", "impact_level", "MODERATE"));
        var fairRow = assessment(fairProfile, Map.of("ale_p90", "12345"));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(nistRow, fairRow));

        RiskHeatmapResult result =
                service.buildHeatmap(projectId, Instant.parse("2026-05-30T00:00:00Z"), nistProfile.getId());

        // NIST row plotted, FAIR row filtered out before contributing to byFamily.
        assertThat(result.cells()).hasSize(1);
        assertThat(result.counts().byMethodologyFamily()).containsExactly(Map.entry("NIST_SP800_30_R1", 1));
        assertThat(result.limitations()).noneMatch(s -> s.contains("FAIR methodology rows are quantitative"));
        assertThat(result.methodologyProfileId()).isEqualTo(nistProfile.getId());
        assertThat(result.methodologyFamily()).isEqualTo("NIST_SP800_30_R1");
    }

    /**
     * Adversarial-review finding #3: when the caller supplies a methodologyProfileId
     * that has zero assessments in the project, the envelope must still carry the
     * requested profile's id+family — resolved via the repository — instead of
     * silently returning nulls.
     */
    @Test
    void profileRestriction_envelopeCarriesRequestedProfileEvenWhenNoRowsMatch() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of());
        when(methodologyProfileRepository.findByIdAndProjectId(nistProfile.getId(), projectId))
                .thenReturn(Optional.of(nistProfile));

        RiskHeatmapResult result =
                service.buildHeatmap(projectId, Instant.parse("2026-05-30T00:00:00Z"), nistProfile.getId());

        assertThat(result.cells()).isEmpty();
        assertThat(result.methodologyProfileId()).isEqualTo(nistProfile.getId());
        assertThat(result.methodologyFamily()).isEqualTo("NIST_SP800_30_R1");
        assertThat(result.inputs().methodologyProfileId()).isEqualTo(nistProfile.getId());
    }

    /**
     * Even when the repository cannot find the requested profile (e.g. caller passed
     * an unknown UUID), the envelope MUST still propagate the requested
     * methodologyProfileId so the contract surface always reflects the request.
     */
    @Test
    void profileRestriction_envelopeCarriesRequestedIdEvenWhenProfileUnknown() {
        UUID unknown = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of());
        when(methodologyProfileRepository.findByIdAndProjectId(unknown, projectId))
                .thenReturn(Optional.empty());

        RiskHeatmapResult result = service.buildHeatmap(projectId, Instant.parse("2026-05-30T00:00:00Z"), unknown);

        assertThat(result.methodologyProfileId()).isEqualTo(unknown);
        // Family is null because we genuinely don't know it; the requested id is
        // still propagated so consumers can correlate against their request.
        assertThat(result.methodologyFamily()).isNull();
        assertThat(result.inputs().methodologyProfileId()).isEqualTo(unknown);
    }
}
