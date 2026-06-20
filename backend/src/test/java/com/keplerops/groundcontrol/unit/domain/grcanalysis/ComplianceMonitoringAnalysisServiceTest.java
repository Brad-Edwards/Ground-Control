package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ComplianceMonitoringAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ComplianceMonitoringResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessResult;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
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
class ComplianceMonitoringAnalysisServiceTest {

    @Mock
    private EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private RiskAssessmentResultRepository assessmentResultRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private ComplianceMonitoringAnalysisService service;

    private Project project;
    private UUID projectId;
    private Instant asOf;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        asOf = Instant.parse("2026-06-20T00:00:00Z");
    }

    @Test
    void analyze_surfacesStaleEvidenceControlChangesAndReassessmentSignals() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        var staleObservation = new EvidenceFreshnessResult.ObservationFreshnessItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ASSET-1",
                "CONFIGURATION",
                "patch-level",
                asOf.minusSeconds(86400L * 120),
                asOf.minusSeconds(86400L),
                120,
                "EXPIRED");
        var freshness = new EvidenceFreshnessResult(
                "evidence_freshness",
                "ground-control",
                asOf,
                "evidence-freshness-projection-v1",
                new EvidenceFreshnessResult.Inputs("ground-control", asOf, 90, false, null, null),
                List.of(),
                List.of(staleObservation),
                List.of(),
                new EvidenceFreshnessResult.EvidenceFreshnessCounts(0, 0, 1, 0, 0),
                List.of());
        when(evidenceFreshnessAnalysisService.analyze(eq(projectId), eq(asOf), eq(90), eq(false), eq(null), eq(null)))
                .thenReturn(freshness);

        var recentControl = new Control(project, "CTRL-RECENT", "Recent control", ControlFunction.PREVENTIVE);
        setField(recentControl, "id", UUID.randomUUID());
        setField(recentControl, "updatedAt", asOf.minusSeconds(3600));
        var oldControl = new Control(project, "CTRL-OLD", "Old control", ControlFunction.DETECTIVE);
        setField(oldControl, "id", UUID.randomUUID());
        setField(oldControl, "updatedAt", asOf.minusSeconds(86400L * 200));
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(recentControl, oldControl));

        var scenario = new RiskScenario(project, "RS-1", "Scenario 1", "Threat", "Method", "Asset", "Effect");
        setField(scenario, "id", UUID.randomUUID());
        var profile = new MethodologyProfile(project, "MP-1", "Profile", "1", MethodologyFamily.NIST_SP800_30_R1);
        setField(profile, "id", UUID.randomUUID());
        var assessment = new RiskAssessmentResult(project, scenario, profile);
        setField(assessment, "id", UUID.randomUUID());
        assessment.setReassessmentRequiredAt(asOf.minusSeconds(7200));
        when(assessmentResultRepository
                        .findByProjectIdWithReassessmentRequiredInWindowOrderByReassessmentRequiredAtDesc(
                                eq(projectId), any(), eq(asOf)))
                .thenReturn(List.of(assessment));

        ComplianceMonitoringResult result = service.analyze(projectId, asOf, 90);

        assertThat(result.analysisKind()).isEqualTo("continuous_compliance_monitoring");
        assertThat(result.staleSet()).hasSize(1);
        assertThat(result.staleSet().getFirst().state()).isEqualTo("EXPIRED");
        assertThat(result.impactSet()).hasSize(2);
        assertThat(result.impactSet())
                .anyMatch(item ->
                        "CONTROL_MODIFICATION".equals(item.driftCause()) && "CTRL-RECENT".equals(item.entityUid()));
        assertThat(result.impactSet())
                .anyMatch(item -> "ARTIFACT_GRAPH_CHANGE".equals(item.driftCause()) && "RS-1".equals(item.entityUid()));
        assertThat(result.driftCauseCounts().evidenceExpiration()).isEqualTo(1);
        assertThat(result.driftCauseCounts().controlModification()).isEqualTo(1);
        assertThat(result.driftCauseCounts().artifactGraphChange()).isEqualTo(1);
        assertThat(result.gapSet()).isEmpty();
        assertThat(result.limitations()).isNotEmpty();
    }

    @Test
    void analyze_defaultsAsOfWhenNull() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(evidenceFreshnessAnalysisService.analyze(any(), any(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(emptyFreshness(Instant.now()));
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());
        when(assessmentResultRepository
                        .findByProjectIdWithReassessmentRequiredInWindowOrderByReassessmentRequiredAtDesc(
                                eq(projectId), any(), any()))
                .thenReturn(List.of());

        ComplianceMonitoringResult result = service.analyze(projectId, null, 90);

        assertThat(result.asOf()).isNotNull();
    }

    @Test
    void analyze_excludesControlUpdatesAfterAsOf() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(evidenceFreshnessAnalysisService.analyze(eq(projectId), eq(asOf), eq(90), eq(false), eq(null), eq(null)))
                .thenReturn(emptyFreshness(asOf));

        var futureControl = new Control(project, "CTRL-FUTURE", "Future control", ControlFunction.PREVENTIVE);
        setField(futureControl, "id", UUID.randomUUID());
        setField(futureControl, "updatedAt", asOf.plusSeconds(3600));
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(futureControl));
        when(assessmentResultRepository
                        .findByProjectIdWithReassessmentRequiredInWindowOrderByReassessmentRequiredAtDesc(
                                eq(projectId), any(), eq(asOf)))
                .thenReturn(List.of());

        ComplianceMonitoringResult result = service.analyze(projectId, asOf, 90);

        assertThat(result.impactSet()).isEmpty();
    }

    private static EvidenceFreshnessResult emptyFreshness(Instant asOf) {
        return new EvidenceFreshnessResult(
                "evidence_freshness",
                "ground-control",
                asOf,
                "evidence-freshness-projection-v1",
                new EvidenceFreshnessResult.Inputs("ground-control", asOf, 90, false, null, null),
                List.of(),
                List.of(),
                List.of(),
                new EvidenceFreshnessResult.EvidenceFreshnessCounts(0, 0, 0, 0, 0),
                List.of());
    }
}
