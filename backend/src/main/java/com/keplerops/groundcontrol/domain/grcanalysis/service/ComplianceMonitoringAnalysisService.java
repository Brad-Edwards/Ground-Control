package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Continuous compliance monitoring per GC-I004. Composes existing substrates
 * (evidence freshness, control update timestamps, reassessment signals) into
 * ADR-058 {@code impact_set} / {@code stale_set} outputs — not a second
 * compliance engine.
 */
@Service
@Transactional(readOnly = true)
public class ComplianceMonitoringAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceMonitoringAnalysisService.class);

    static final String ANALYSIS_KIND = "continuous_compliance_monitoring";
    static final String DERIVATION_METHOD = "continuous-compliance-monitoring-v1";

    static final String DRIFT_CONTROL_MODIFICATION = "CONTROL_MODIFICATION";
    static final String DRIFT_ARTIFACT_GRAPH_CHANGE = "ARTIFACT_GRAPH_CHANGE";

    static final String ENTITY_CONTROL = "CONTROL";
    static final String ENTITY_RISK_ASSESSMENT_RESULT = "RISK_ASSESSMENT_RESULT";

    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;
    private final ControlRepository controlRepository;
    private final RiskAssessmentResultRepository assessmentResultRepository;
    private final ProjectRepository projectRepository;

    public ComplianceMonitoringAnalysisService(
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService,
            ControlRepository controlRepository,
            RiskAssessmentResultRepository assessmentResultRepository,
            ProjectRepository projectRepository) {
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
        this.controlRepository = controlRepository;
        this.assessmentResultRepository = assessmentResultRepository;
        this.projectRepository = projectRepository;
    }

    public ComplianceMonitoringResult analyze(UUID projectId, Instant asOf, int freshnessWindowDays) {
        Objects.requireNonNull(projectId, "projectId");
        if (freshnessWindowDays <= 0) {
            throw new DomainValidationException(
                    "freshnessWindowDays must be positive",
                    "validation_error",
                    java.util.Map.of("field", "freshnessWindowDays"));
        }

        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        var project = projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        Instant lookbackStart = effectiveAsOf.minus(Duration.ofDays(freshnessWindowDays));

        var freshness = evidenceFreshnessAnalysisService.analyze(
                projectId, effectiveAsOf, freshnessWindowDays, false, null, null);

        var staleSet = buildStaleSet(freshness);
        var impactSet = new ArrayList<ComplianceMonitoringResult.ImpactItem>();
        impactSet.addAll(buildControlModificationImpacts(projectId, lookbackStart, effectiveAsOf));
        impactSet.addAll(buildReassessmentImpacts(projectId, lookbackStart, effectiveAsOf));

        int evidenceExpirationCount = staleSet.size();
        int controlModificationCount = (int) impactSet.stream()
                .filter(item -> DRIFT_CONTROL_MODIFICATION.equals(item.driftCause()))
                .count();
        int artifactGraphChangeCount = (int) impactSet.stream()
                .filter(item -> DRIFT_ARTIFACT_GRAPH_CHANGE.equals(item.driftCause()))
                .count();

        var limitations = new ArrayList<String>();
        limitations.add("gap_set is empty in v1; derivation-backed coverage gaps are deferred to GC-GRC-009 / ADR-058");
        limitations.add("artifact-graph change detection uses reassessmentRequiredAt signals (GC-T004/C8); "
                + "derivation diff and traceability-link change causes are future extensions");

        log.info(
                "grcanalysis.compliance_monitoring analyzed: project={} asOf={} impact={} stale={}",
                project.getIdentifier(),
                effectiveAsOf,
                impactSet.size(),
                staleSet.size());

        return new ComplianceMonitoringResult(
                ANALYSIS_KIND,
                project.getIdentifier(),
                effectiveAsOf,
                DERIVATION_METHOD,
                new ComplianceMonitoringResult.Inputs(project.getIdentifier(), effectiveAsOf, freshnessWindowDays),
                List.copyOf(impactSet),
                List.of(),
                List.copyOf(staleSet),
                new ComplianceMonitoringResult.DriftCauseCounts(
                        controlModificationCount, artifactGraphChangeCount, evidenceExpirationCount),
                List.copyOf(limitations));
    }

    private List<ComplianceMonitoringResult.StaleItem> buildStaleSet(EvidenceFreshnessResult freshness) {
        var stale = new ArrayList<ComplianceMonitoringResult.StaleItem>();
        for (var item : freshness.evidenceArtifacts()) {
            if (isStaleState(item.state())) {
                stale.add(new ComplianceMonitoringResult.StaleItem(
                        "EVIDENCE_ARTIFACT", item.id(), item.uid(), item.state(), freshness.asOf()));
            }
        }
        for (var item : freshness.observations()) {
            if (isStaleState(item.state())) {
                stale.add(new ComplianceMonitoringResult.StaleItem(
                        "OBSERVATION", item.id(), item.observationKey(), item.state(), freshness.asOf()));
            }
        }
        for (var item : freshness.controlTests()) {
            if (isStaleState(item.state())) {
                stale.add(new ComplianceMonitoringResult.StaleItem(
                        "CONTROL_TEST", item.id(), item.uid(), item.state(), freshness.asOf()));
            }
        }
        return stale;
    }

    private static boolean isStaleState(String state) {
        return EvidenceFreshnessAnalysisService.STATE_STALE.equals(state)
                || EvidenceFreshnessAnalysisService.STATE_EXPIRED.equals(state);
    }

    private List<ComplianceMonitoringResult.ImpactItem> buildControlModificationImpacts(
            UUID projectId, Instant lookbackStart, Instant effectiveAsOf) {
        return controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(control -> isWithinWindow(control.getUpdatedAt(), lookbackStart, effectiveAsOf))
                .map(ComplianceMonitoringAnalysisService::toControlImpact)
                .toList();
    }

    private static ComplianceMonitoringResult.ImpactItem toControlImpact(Control control) {
        return new ComplianceMonitoringResult.ImpactItem(
                DRIFT_CONTROL_MODIFICATION,
                ENTITY_CONTROL,
                control.getId(),
                control.getUid(),
                control.getUpdatedAt(),
                "Control modified within lookback window");
    }

    private List<ComplianceMonitoringResult.ImpactItem> buildReassessmentImpacts(
            UUID projectId, Instant lookbackStart, Instant effectiveAsOf) {
        return assessmentResultRepository
                .findByProjectIdWithReassessmentRequiredInWindowOrderByReassessmentRequiredAtDesc(
                        projectId, lookbackStart, effectiveAsOf)
                .stream()
                .map(this::toReassessmentImpact)
                .toList();
    }

    private static boolean isWithinWindow(Instant timestamp, Instant lookbackStart, Instant effectiveAsOf) {
        return !timestamp.isBefore(lookbackStart) && !timestamp.isAfter(effectiveAsOf);
    }

    private ComplianceMonitoringResult.ImpactItem toReassessmentImpact(RiskAssessmentResult assessment) {
        Instant detectedAt = assessment.getReassessmentRequiredAt() != null
                ? assessment.getReassessmentRequiredAt()
                : assessment.getUpdatedAt();
        String scenarioUid = assessment.getRiskScenario().getUid();
        return new ComplianceMonitoringResult.ImpactItem(
                DRIFT_ARTIFACT_GRAPH_CHANGE,
                ENTITY_RISK_ASSESSMENT_RESULT,
                assessment.getId(),
                scenarioUid,
                detectedAt,
                "Risk assessment requires reassessment after upstream artifact/control/asset change");
    }
}
