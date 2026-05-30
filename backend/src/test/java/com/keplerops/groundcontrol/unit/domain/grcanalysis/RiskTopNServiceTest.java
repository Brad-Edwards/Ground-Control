package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTopNOrderBy;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTopNResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTopNService;
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
class RiskTopNServiceTest {

    @Mock
    private RiskAssessmentResultRepository repository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private RiskTopNService service;

    private Project project;
    private UUID projectId;
    private MethodologyProfile nistProfile;
    private MethodologyProfile fairProfile;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        nistProfile = new MethodologyProfile(project, "NIST", "NIST", "1", MethodologyFamily.NIST_SP800_30_R1);
        setField(nistProfile, "id", UUID.randomUUID());
        fairProfile = new MethodologyProfile(project, "FAIR", "FAIR", "1", MethodologyFamily.FAIR);
        setField(fairProfile, "id", UUID.randomUUID());
    }

    private RiskAssessmentResult assessment(MethodologyProfile profile, String riskLevel, Instant assessmentAt) {
        var scenario = new RiskScenario(project, "RS-" + UUID.randomUUID(), "scenario", "T", "M", "A", "E");
        setField(scenario, "id", UUID.randomUUID());
        var r = new RiskAssessmentResult(project, scenario, profile);
        setField(r, "id", UUID.randomUUID());
        setField(r, "createdAt", assessmentAt != null ? assessmentAt : Instant.now());
        if (riskLevel != null) {
            r.setComputedOutputs(Map.of("risk_level", riskLevel));
        }
        r.setAssessmentAt(assessmentAt);
        return r;
    }

    @Test
    void ranksByRiskLevelDescending_andReturnsLimit() {
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        var high = assessment(nistProfile, "HIGH", now);
        var moderate = assessment(nistProfile, "MODERATE", now);
        var low = assessment(nistProfile, "LOW", now);
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(low, moderate, high));

        RiskTopNResult result = service.topN(projectId, now, 2, RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT);

        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).rankingValue()).isEqualTo("HIGH");
        assertThat(result.entries().get(0).rank()).isEqualTo(1);
        assertThat(result.entries().get(1).rankingValue()).isEqualTo("MODERATE");
        assertThat(result.counts().totalConsidered()).isEqualTo(3);
        assertThat(result.counts().totalReturned()).isEqualTo(2);
    }

    @Test
    void rowsWithoutRiskLevel_areExcludedAndCounted() {
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        var withLevel = assessment(nistProfile, "HIGH", now);
        var withoutLevel = assessment(nistProfile, null, now);
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(withLevel, withoutLevel));

        RiskTopNResult result = service.topN(projectId, now, 5, RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT);

        assertThat(result.entries()).hasSize(1);
        assertThat(result.limitations()).anyMatch(s -> s.contains("no ranking value"));
    }

    @Test
    void mixedMethodologies_emitsLimitation() {
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        var nistRow = assessment(nistProfile, "HIGH", now);
        var fairRow = assessment(fairProfile, null, now);
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(nistRow, fairRow));

        RiskTopNResult result = service.topN(projectId, now, 5, RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT);

        assertThat(result.limitations()).anyMatch(s -> s.contains("multiple methodology families"));
        assertThat(result.limitations()).anyMatch(s -> s.contains("FAIR methodology rows do not produce"));
    }

    @Test
    void orderByAssessmentAt_sortsByRecency() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-05-30T00:00:00Z");
        var oldRow = assessment(nistProfile, "HIGH", older);
        var newRow = assessment(nistProfile, "LOW", newer);
        when(repository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of(oldRow, newRow));

        RiskTopNResult result = service.topN(projectId, newer, 5, RiskTopNOrderBy.ASSESSMENT_AT_DESC);

        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).riskAssessmentResultId()).isEqualTo(newRow.getId());
        assertThat(result.entries().get(0).rankingMetric()).isEqualTo("assessment_at");
    }

    @Test
    void invalidLimit_throwsValidation() {
        assertThatThrownBy(() -> service.topN(projectId, null, 0, RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> service.topN(projectId, null, 500, RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void projectNotFound_throws() {
        UUID missing = UUID.randomUUID();
        when(projectRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.topN(missing, null, 5, RiskTopNOrderBy.ASSESSMENT_AT_DESC))
                .isInstanceOf(NotFoundException.class);
    }
}
