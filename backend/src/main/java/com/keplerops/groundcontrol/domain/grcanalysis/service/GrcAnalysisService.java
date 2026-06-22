package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin orchestrator that delegates to GRC analysis services
 * ({@link EvidenceFreshnessAnalysisService},
 * {@link ObservationProjectionService},
 * {@link VendorRiskAggregationService},
 * {@link NistAssessmentService},
 * {@link FairQuantitativeAnalysisService},
 * {@link ComplianceMonitoringAnalysisService},
 * {@link FairCamControlAnalyticsService},
 * {@link RiskAppetiteEvaluationService}).
 */
@Service
@Transactional(readOnly = true)
public class GrcAnalysisService {

    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;
    private final ObservationProjectionService observationProjectionService;
    private final VendorRiskAggregationService vendorRiskAggregationService;
    private final NistAssessmentService nistAssessmentService;
    private final FairQuantitativeAnalysisService fairQuantitativeAnalysisService;
    private final ComplianceMonitoringAnalysisService complianceMonitoringAnalysisService;
    private final FairCamControlAnalyticsService fairCamControlAnalyticsService;
    private final RiskAppetiteEvaluationService riskAppetiteEvaluationService;

    public GrcAnalysisService(
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService,
            ObservationProjectionService observationProjectionService,
            VendorRiskAggregationService vendorRiskAggregationService,
            NistAssessmentService nistAssessmentService,
            FairQuantitativeAnalysisService fairQuantitativeAnalysisService,
            ComplianceMonitoringAnalysisService complianceMonitoringAnalysisService,
            FairCamControlAnalyticsService fairCamControlAnalyticsService,
            RiskAppetiteEvaluationService riskAppetiteEvaluationService) {
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
        this.observationProjectionService = observationProjectionService;
        this.vendorRiskAggregationService = vendorRiskAggregationService;
        this.nistAssessmentService = nistAssessmentService;
        this.fairQuantitativeAnalysisService = fairQuantitativeAnalysisService;
        this.complianceMonitoringAnalysisService = complianceMonitoringAnalysisService;
        this.fairCamControlAnalyticsService = fairCamControlAnalyticsService;
        this.riskAppetiteEvaluationService = riskAppetiteEvaluationService;
    }

    public EvidenceFreshnessResult evidenceFreshness(
            UUID projectId,
            Instant asOf,
            int freshnessWindowDays,
            boolean includeSuperseded,
            UUID assetId,
            UUID controlId) {
        return evidenceFreshnessAnalysisService.analyze(
                projectId, asOf, freshnessWindowDays, includeSuperseded, assetId, controlId);
    }

    public ObservationProjectionResult observationProjection(
            UUID projectId, Instant asOf, ObservationProjectionMode mode, UUID assetId, UUID controlId) {
        return observationProjectionService.project(projectId, asOf, mode, assetId, controlId);
    }

    public VendorRiskAggregationResult vendorRisk(
            UUID projectId, Instant asOf, int freshnessWindowDays, UUID vendorAssetId) {
        return vendorRiskAggregationService.aggregate(projectId, asOf, freshnessWindowDays, vendorAssetId);
    }

    public NistAssessmentResult nistAssessment(
            UUID projectId, Instant asOf, UUID riskAssessmentResultId, UUID riskScenarioId) {
        return nistAssessmentService.analyze(projectId, asOf, riskAssessmentResultId, riskScenarioId);
    }

    public FairQuantitativeAnalysisResult fairQuantitative(
            UUID projectId, Instant asOf, UUID riskAssessmentResultId, UUID riskScenarioId) {
        return fairQuantitativeAnalysisService.analyze(projectId, asOf, riskAssessmentResultId, riskScenarioId);
    }

    public RiskAppetiteEvaluationResult riskAppetiteEvaluation(
            UUID projectId,
            Instant asOf,
            UUID profileId,
            String appetiteKey,
            UUID riskRegisterRecordId,
            UUID riskScenarioId) {
        return riskAppetiteEvaluationService.evaluate(
                projectId, asOf, profileId, appetiteKey, riskRegisterRecordId, riskScenarioId);
    }

    public ComplianceMonitoringResult complianceMonitoring(UUID projectId, Instant asOf, int freshnessWindowDays) {
        return complianceMonitoringAnalysisService.analyze(projectId, asOf, freshnessWindowDays);
    }

    public FairCamControlAnalyticsResult fairCamControlAnalytics(UUID projectId, FairCamControlAnalyticsQuery query) {
        return fairCamControlAnalyticsService.analyze(projectId, query);
    }
}
