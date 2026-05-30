package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsBucket;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class RiskTrendsServiceTest {

    @Mock
    private RiskRegisterRecordRepository registerRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private RiskTrendsService service;

    private Project project;
    private UUID projectId;
    private static final Instant ASOF = Instant.parse("2026-05-30T00:00:00Z");

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
    }

    @Test
    void noRecords_shortCircuitsWithoutEnversCall() {
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        RiskTrendsResult result = service.trends(projectId, ASOF, null, null, RiskTrendsBucket.MONTH);

        assertThat(result.analysisKind()).isEqualTo("risk_trends");
        assertThat(result.derivationMethod()).isEqualTo("risk-register-envers-audit-trends-v1");
        assertThat(result.scale()).isEqualTo("count");
        assertThat(result.units()).isEqualTo("audit revisions per bucket");
        assertThat(result.counts().totalEvents()).isZero();
        assertThat(result.counts().totalBuckets()).isZero();
        assertThat(result.inputs().bucket()).isEqualTo("MONTH");
        assertThat(result.inputs().entity()).isEqualTo("RiskRegisterRecord");
        // No EntityManager calls when there are no project record ids to query.
        verifyNoInteractions(entityManager);
    }

    @Test
    void defaultedFrom_emitsLimitation() {
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        RiskTrendsResult result = service.trends(projectId, ASOF, null, ASOF, RiskTrendsBucket.MONTH);

        assertThat(result.limitations()).anyMatch(s -> s.contains("12 months"));
        assertThat(result.inputs().from()).isEqualTo(ASOF.minus(365, ChronoUnit.DAYS));
        assertThat(result.inputs().to()).isEqualTo(ASOF);
    }

    @Test
    void invalidWindow_fromAfterTo_throws() {
        Instant from = ASOF.plus(1, ChronoUnit.DAYS);
        Instant to = ASOF;
        assertThatThrownBy(() -> service.trends(projectId, ASOF, from, to, RiskTrendsBucket.MONTH))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void invalidWindow_tooLarge_throws() {
        Instant from = ASOF.minus(365L * 10, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.trends(projectId, ASOF, from, ASOF, RiskTrendsBucket.MONTH))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void projectNotFound_throws() {
        UUID missing = UUID.randomUUID();
        when(projectRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trends(missing, null, null, null, RiskTrendsBucket.MONTH))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void bucketAndEntityCarriedInInputs() {
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());
        Instant from = ASOF.minus(30, ChronoUnit.DAYS);

        RiskTrendsResult result = service.trends(projectId, ASOF, from, ASOF, RiskTrendsBucket.WEEK);

        assertThat(result.inputs().bucket()).isEqualTo("WEEK");
        assertThat(result.inputs().from()).isEqualTo(from);
        assertThat(result.inputs().to()).isEqualTo(ASOF);
        verify(registerRepository).findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId);
    }
}
