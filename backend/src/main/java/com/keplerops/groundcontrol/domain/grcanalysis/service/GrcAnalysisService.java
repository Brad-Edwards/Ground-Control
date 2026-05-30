package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin orchestrator that delegates to the GRC analysis services. Keeps
 * controllers thin and gives the extension seam from the preflight a single
 * class to point at. Wired delegates: {@link EvidenceFreshnessAnalysisService},
 * {@link ObservationProjectionService}, {@link VendorRiskAggregationService},
 * {@link NistAssessmentService}, {@link PortfolioAggregationService}, and the
 * GC-T008 cluster of methodology-aware aggregate risk reporting services
 * (heat map, distribution, top-N, trends, posture).
 */
@Service
@Transactional(readOnly = true)
public class GrcAnalysisService {

    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;
    private final ObservationProjectionService observationProjectionService;
    private final VendorRiskAggregationService vendorRiskAggregationService;
    private final NistAssessmentService nistAssessmentService;
    private final PortfolioAggregationService portfolioAggregationService;
    private final RiskHeatmapService riskHeatmapService;
    private final RiskDistributionService riskDistributionService;
    private final RiskTopNService riskTopNService;
    private final RiskTrendsService riskTrendsService;
    private final RiskPostureService riskPostureService;

    @SuppressWarnings("java:S107") // orchestrator: each delegate is a distinct read-only projection service.
    public GrcAnalysisService(
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService,
            ObservationProjectionService observationProjectionService,
            VendorRiskAggregationService vendorRiskAggregationService,
            NistAssessmentService nistAssessmentService,
            PortfolioAggregationService portfolioAggregationService,
            RiskHeatmapService riskHeatmapService,
            RiskDistributionService riskDistributionService,
            RiskTopNService riskTopNService,
            RiskTrendsService riskTrendsService,
            RiskPostureService riskPostureService) {
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
        this.observationProjectionService = observationProjectionService;
        this.vendorRiskAggregationService = vendorRiskAggregationService;
        this.nistAssessmentService = nistAssessmentService;
        this.portfolioAggregationService = portfolioAggregationService;
        this.riskHeatmapService = riskHeatmapService;
        this.riskDistributionService = riskDistributionService;
        this.riskTopNService = riskTopNService;
        this.riskTrendsService = riskTrendsService;
        this.riskPostureService = riskPostureService;
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

    public PortfolioSummaryResult portfolio(UUID projectId, Instant asOf, int freshnessWindowDays) {
        return portfolioAggregationService.summarize(projectId, asOf, freshnessWindowDays);
    }

    public RiskHeatmapResult riskHeatmap(UUID projectId, Instant asOf, UUID methodologyProfileId) {
        return riskHeatmapService.buildHeatmap(projectId, asOf, methodologyProfileId);
    }

    public RiskDistributionResult riskDistribution(UUID projectId, Instant asOf, RiskDistributionGroupBy groupBy) {
        return riskDistributionService.distribute(projectId, asOf, groupBy);
    }

    public RiskTopNResult riskTopN(UUID projectId, Instant asOf, int limit, RiskTopNOrderBy orderBy) {
        return riskTopNService.topN(projectId, asOf, limit, orderBy);
    }

    public RiskTrendsResult riskTrends(
            UUID projectId, Instant asOf, Instant from, Instant to, RiskTrendsBucket bucket) {
        return riskTrendsService.trends(projectId, asOf, from, to, bucket);
    }

    public RiskPostureResult riskPosture(UUID projectId, Instant asOf) {
        return riskPostureService.posture(projectId, asOf);
    }
}
