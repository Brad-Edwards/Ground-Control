package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin orchestrator that delegates to the five GRC analysis services
 * ({@link EvidenceFreshnessAnalysisService},
 * {@link ObservationProjectionService},
 * {@link VendorRiskAggregationService},
 * {@link NistAssessmentService},
 * {@link FairQuantitativeAnalysisService}). Keeps controllers thin and gives the
 * extension seam from the preflight a single class to point at.
 */
@Service
@Transactional(readOnly = true)
public class GrcAnalysisService {

    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;
    private final ObservationProjectionService observationProjectionService;
    private final VendorRiskAggregationService vendorRiskAggregationService;
    private final NistAssessmentService nistAssessmentService;
    private final FairQuantitativeAnalysisService fairQuantitativeAnalysisService;

    public GrcAnalysisService(
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService,
            ObservationProjectionService observationProjectionService,
            VendorRiskAggregationService vendorRiskAggregationService,
            NistAssessmentService nistAssessmentService,
            FairQuantitativeAnalysisService fairQuantitativeAnalysisService) {
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
        this.observationProjectionService = observationProjectionService;
        this.vendorRiskAggregationService = vendorRiskAggregationService;
        this.nistAssessmentService = nistAssessmentService;
        this.fairQuantitativeAnalysisService = fairQuantitativeAnalysisService;
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
}
