package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskPostureResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskPostureService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskRegisterStatus;
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
class RiskPostureServiceTest {

    @Mock
    private RiskRegisterRecordRepository registerRepository;

    @Mock
    private RiskAssessmentResultRepository assessmentRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private RiskPostureService service;

    private Project project;
    private UUID projectId;
    private MethodologyProfile nistProfile;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        nistProfile = new MethodologyProfile(project, "NIST", "NIST", "1", MethodologyFamily.NIST_SP800_30_R1);
        setField(nistProfile, "id", UUID.randomUUID());
    }

    private RiskRegisterRecord record(RiskRegisterStatus status) {
        var r = new RiskRegisterRecord(project, "RR-" + UUID.randomUUID(), "title");
        setField(r, "id", UUID.randomUUID());
        setField(r, "status", status);
        return r;
    }

    private RiskAssessmentResult assessment(RiskAssessmentApprovalStatus state, Instant reassessmentRequiredAt) {
        var scenario = new RiskScenario(project, "RS-" + UUID.randomUUID(), "scenario", "T", "M", "A", "E");
        setField(scenario, "id", UUID.randomUUID());
        var r = new RiskAssessmentResult(project, scenario, nistProfile);
        setField(r, "id", UUID.randomUUID());
        if (state != null) {
            setField(r, "approvalState", state);
        }
        r.setReassessmentRequiredAt(reassessmentRequiredAt);
        return r;
    }

    @Test
    void aggregatesStatusOpenAcceptedClosedAndEmitsAppetiteDeferral() {
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        var open1 = record(RiskRegisterStatus.IDENTIFIED);
        var open2 = record(RiskRegisterStatus.TREATING);
        var accepted = record(RiskRegisterStatus.ACCEPTED);
        var closed = record(RiskRegisterStatus.CLOSED);
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(open1, open2, accepted, closed));
        when(assessmentRepository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of());

        RiskPostureResult result = service.posture(projectId, now);

        assertThat(result.analysisKind()).isEqualTo("risk_posture");
        assertThat(result.statusSummary().totalRecords()).isEqualTo(4);
        assertThat(result.statusSummary().openCount()).isEqualTo(2);
        assertThat(result.statusSummary().acceptedCount()).isEqualTo(1);
        assertThat(result.statusSummary().closedCount()).isEqualTo(1);
        assertThat(result.statusSummary().byStatus()).containsKeys("IDENTIFIED", "TREATING", "ACCEPTED", "CLOSED");
        assertThat(result.limitations()).anyMatch(s -> s.contains("RiskAppetiteEvaluator"));
    }

    @Test
    void approvalAndReassessmentCounts_aggregateLatestPerScenario() {
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        var draftAssessment = assessment(RiskAssessmentApprovalStatus.DRAFT, null);
        var approvedAssessment = assessment(RiskAssessmentApprovalStatus.APPROVED, null);
        var pendingReassessment = assessment(RiskAssessmentApprovalStatus.APPROVED, now);
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(record(RiskRegisterStatus.IDENTIFIED)));
        when(assessmentRepository.findLatestPerScenarioByProjectId(projectId))
                .thenReturn(List.of(draftAssessment, approvedAssessment, pendingReassessment));

        RiskPostureResult result = service.posture(projectId, now);

        assertThat(result.approvalSummary().totalAssessments()).isEqualTo(3);
        assertThat(result.approvalSummary().byApprovalState()).containsEntry("DRAFT", 1);
        assertThat(result.approvalSummary().byApprovalState()).containsEntry("APPROVED", 2);
        assertThat(result.reassessmentSummary().pendingReassessmentCount()).isEqualTo(1);
        assertThat(result.reassessmentSummary().totalAssessmentsConsidered()).isEqualTo(3);
    }

    @Test
    void emptyProject_emitsNoRecordsLimitation() {
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());
        when(assessmentRepository.findLatestPerScenarioByProjectId(projectId)).thenReturn(List.of());

        RiskPostureResult result = service.posture(projectId, now);

        assertThat(result.limitations()).anyMatch(s -> s.contains("no RiskRegisterRecord rows"));
    }

    @Test
    void projectNotFound_throws() {
        UUID missing = UUID.randomUUID();
        when(projectRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.posture(missing, null)).isInstanceOf(NotFoundException.class);
    }
}
