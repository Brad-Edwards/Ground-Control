package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.GapSeverity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin orchestrator that delegates to the GRC analysis services. Keeps
 * controllers thin and gives the extension seam from the preflight a single
 * class to point at. Wired delegates: {@link EvidenceFreshnessAnalysisService},
 * {@link ObservationProjectionService}, {@link VendorRiskAggregationService},
 * {@link NistAssessmentService}, and {@link PortfolioAggregationService}.
 * Cluster-3 (GC-T008) risk analysis projections are grouped behind
 * {@link RiskAnalysisOrchestrator}; GC-I002 / GC-I007 compliance analyses
 * behind {@link ComplianceAnalysisOrchestrator}—to keep the dependency count
 * within the Monster-Class limit.
 */
@Service
@Transactional(readOnly = true)
public class GrcAnalysisService {

    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;
    private final ObservationProjectionService observationProjectionService;
    private final VendorRiskAggregationService vendorRiskAggregationService;
    private final NistAssessmentService nistAssessmentService;
    private final PortfolioAggregationService portfolioAggregationService;
    private final RiskAnalysisOrchestrator riskAnalysisOrchestrator;
    private final ComplianceAnalysisOrchestrator complianceAnalysisOrchestrator;

    public GrcAnalysisService(
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService,
            ObservationProjectionService observationProjectionService,
            VendorRiskAggregationService vendorRiskAggregationService,
            NistAssessmentService nistAssessmentService,
            PortfolioAggregationService portfolioAggregationService,
            RiskAnalysisOrchestrator riskAnalysisOrchestrator,
            ComplianceAnalysisOrchestrator complianceAnalysisOrchestrator) {
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
        this.observationProjectionService = observationProjectionService;
        this.vendorRiskAggregationService = vendorRiskAggregationService;
        this.nistAssessmentService = nistAssessmentService;
        this.portfolioAggregationService = portfolioAggregationService;
        this.riskAnalysisOrchestrator = riskAnalysisOrchestrator;
        this.complianceAnalysisOrchestrator = complianceAnalysisOrchestrator;
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
        return riskAnalysisOrchestrator.heatmap(projectId, asOf, methodologyProfileId);
    }

    public RiskDistributionResult riskDistribution(UUID projectId, Instant asOf, RiskDistributionGroupBy groupBy) {
        return riskAnalysisOrchestrator.distribution(projectId, asOf, groupBy);
    }

    public RiskTopNResult riskTopN(UUID projectId, Instant asOf, int limit, RiskTopNOrderBy orderBy) {
        return riskAnalysisOrchestrator.topN(projectId, asOf, limit, orderBy);
    }

    public RiskTrendsResult riskTrends(
            UUID projectId, Instant asOf, Instant from, Instant to, RiskTrendsBucket bucket) {
        return riskAnalysisOrchestrator.trends(projectId, asOf, from, to, bucket);
    }

    public RiskPostureResult riskPosture(UUID projectId, Instant asOf) {
        return riskAnalysisOrchestrator.posture(projectId, asOf);
    }

    public CompliancePostureResult compliancePosture(
            UUID projectId, Instant asOf, ComplianceFrameworkIdentifier framework) {
        return complianceAnalysisOrchestrator.posture(projectId, asOf, framework);
    }

    public CrossFrameworkGapResult crossFrameworkGap(
            UUID projectId, Instant asOf, ComplianceFrameworkIdentifier framework, GapSeverity minSeverity) {
        return complianceAnalysisOrchestrator.gap(projectId, asOf, framework, minSeverity);
    }
}
