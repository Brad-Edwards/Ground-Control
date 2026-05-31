package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin orchestrator grouping the five GC-T008 cluster-3 risk analysis
 * projections (heat map, distribution, top-N, trends, posture) behind a
 * single injectable facade. This keeps {@link GrcAnalysisService} below
 * the Monster-Class coupling threshold while each projection service
 * retains its own transactional boundary.
 */
@Service
@Transactional(readOnly = true)
public class RiskAnalysisOrchestrator {

    private final RiskHeatmapService riskHeatmapService;
    private final RiskDistributionService riskDistributionService;
    private final RiskTopNService riskTopNService;
    private final RiskTrendsService riskTrendsService;
    private final RiskPostureService riskPostureService;

    public RiskAnalysisOrchestrator(
            RiskHeatmapService riskHeatmapService,
            RiskDistributionService riskDistributionService,
            RiskTopNService riskTopNService,
            RiskTrendsService riskTrendsService,
            RiskPostureService riskPostureService) {
        this.riskHeatmapService = riskHeatmapService;
        this.riskDistributionService = riskDistributionService;
        this.riskTopNService = riskTopNService;
        this.riskTrendsService = riskTrendsService;
        this.riskPostureService = riskPostureService;
    }

    public RiskHeatmapResult heatmap(UUID projectId, Instant asOf, UUID methodologyProfileId) {
        return riskHeatmapService.buildHeatmap(projectId, asOf, methodologyProfileId);
    }

    public RiskDistributionResult distribution(UUID projectId, Instant asOf, RiskDistributionGroupBy groupBy) {
        return riskDistributionService.distribute(projectId, asOf, groupBy);
    }

    public RiskTopNResult topN(UUID projectId, Instant asOf, int limit, RiskTopNOrderBy orderBy) {
        return riskTopNService.topN(projectId, asOf, limit, orderBy);
    }

    public RiskTrendsResult trends(UUID projectId, Instant asOf, Instant from, Instant to, RiskTrendsBucket bucket) {
        return riskTrendsService.trends(projectId, asOf, from, to, bucket);
    }

    public RiskPostureResult posture(UUID projectId, Instant asOf) {
        return riskPostureService.posture(projectId, asOf);
    }
}
