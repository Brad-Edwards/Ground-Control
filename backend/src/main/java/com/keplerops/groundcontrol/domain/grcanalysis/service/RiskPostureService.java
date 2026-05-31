package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskRegisterStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-T008 executive risk posture summary. Aggregates the project's
 * {@link RiskRegisterRecord} status distribution, the latest-per-scenario
 * {@link RiskAssessmentResult} approval-state distribution, and the count of
 * scenarios flagged for reassessment ({@code reassessmentRequiredAt} non-null
 * — written by the C8 listener).
 *
 * <p>The cluster-3 architectural note routes detailed appetite/tolerance
 * evaluation through a shared {@code RiskAppetiteEvaluator} kernel landing in
 * cluster 1. Until that kernel ships, the response carries an explicit
 * {@code limitations} entry making the deferral visible; downstream consumers
 * must not interpret status counts as appetite-conforming posture.
 */
@Service
@Transactional(readOnly = true)
public class RiskPostureService {

    static final String ANALYSIS_KIND = "risk_posture";
    static final String DERIVATION_METHOD = "risk-register-and-approval-state-rollup-v1";
    static final String SCALE = "count";
    static final String UNITS = "register records and approval-state counts";
    static final String APPETITE_KERNEL_PENDING_LIMITATION =
            "appetite/tolerance evaluation deferred to the shared RiskAppetiteEvaluator kernel from"
                    + " cluster 1 (GC-T005); posture summary reports status / approval-state distributions"
                    + " only — do not interpret as appetite-conforming posture";
    static final String NO_RECORDS_LIMITATION = "project has no RiskRegisterRecord rows; posture is empty";

    private static final EnumSet<RiskRegisterStatus> OPEN_STATES = EnumSet.of(
            RiskRegisterStatus.IDENTIFIED,
            RiskRegisterStatus.ANALYZING,
            RiskRegisterStatus.ASSESSED,
            RiskRegisterStatus.TREATING,
            RiskRegisterStatus.MONITORING);

    private final RiskRegisterRecordRepository registerRepository;
    private final RiskAssessmentResultRepository assessmentRepository;
    private final ProjectRepository projectRepository;

    public RiskPostureService(
            RiskRegisterRecordRepository registerRepository,
            RiskAssessmentResultRepository assessmentRepository,
            ProjectRepository projectRepository) {
        this.registerRepository = registerRepository;
        this.assessmentRepository = assessmentRepository;
        this.projectRepository = projectRepository;
    }

    public RiskPostureResult posture(UUID projectId, Instant asOf) {
        Objects.requireNonNull(projectId, "projectId");
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();

        Project project = projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        List<RiskRegisterRecord> records =
                registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId);
        List<RiskAssessmentResult> latestAssessments = assessmentRepository.findLatestPerScenarioByProjectId(projectId);

        TreeMap<String, Integer> byStatus = new TreeMap<>();
        int open = 0;
        int accepted = 0;
        int closed = 0;
        for (RiskRegisterRecord registerRecord : records) {
            RiskRegisterStatus status = registerRecord.getStatus();
            if (status == null) {
                continue;
            }
            byStatus.merge(status.name(), 1, Integer::sum);
            if (status == RiskRegisterStatus.ACCEPTED) {
                accepted++;
            } else if (status == RiskRegisterStatus.CLOSED) {
                closed++;
            } else if (OPEN_STATES.contains(status)) {
                open++;
            }
        }

        TreeMap<String, Integer> byApprovalState = new TreeMap<>();
        int pendingReassessment = 0;
        for (RiskAssessmentResult assessment : latestAssessments) {
            if (assessment.getApprovalState() != null) {
                byApprovalState.merge(assessment.getApprovalState().name(), 1, Integer::sum);
            }
            if (assessment.getReassessmentRequiredAt() != null) {
                pendingReassessment++;
            }
        }

        List<String> limitations = new ArrayList<>();
        limitations.add(APPETITE_KERNEL_PENDING_LIMITATION);
        if (records.isEmpty()) {
            limitations.add(NO_RECORDS_LIMITATION);
        }

        return new RiskPostureResult(
                ANALYSIS_KIND,
                project.getIdentifier(),
                effectiveAsOf,
                DERIVATION_METHOD,
                SCALE,
                UNITS,
                new RiskPostureResult.Inputs(project.getIdentifier(), effectiveAsOf),
                new RiskPostureResult.StatusSummary(records.size(), open, accepted, closed, byStatus),
                new RiskPostureResult.ApprovalSummary(latestAssessments.size(), byApprovalState),
                new RiskPostureResult.ReassessmentSummary(pendingReassessment, latestAssessments.size()),
                limitations);
    }
}
