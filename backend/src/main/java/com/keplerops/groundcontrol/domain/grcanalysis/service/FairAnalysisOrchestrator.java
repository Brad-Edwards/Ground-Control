package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin orchestrator grouping the two GC-T011 / GC-I017 FAIR analytics services
 * ({@link FairQuantitativeAnalysisService} and {@link FairCamControlAnalyticsService})
 * behind a single injectable facade. This keeps {@link GrcAnalysisService} below
 * the Monster-Class coupling threshold while each service retains its own
 * transactional boundary.
 */
@Service
@Transactional(readOnly = true)
public class FairAnalysisOrchestrator {

    private final FairQuantitativeAnalysisService fairQuantitativeAnalysisService;
    private final FairCamControlAnalyticsService fairCamControlAnalyticsService;

    public FairAnalysisOrchestrator(
            FairQuantitativeAnalysisService fairQuantitativeAnalysisService,
            FairCamControlAnalyticsService fairCamControlAnalyticsService) {
        this.fairQuantitativeAnalysisService = fairQuantitativeAnalysisService;
        this.fairCamControlAnalyticsService = fairCamControlAnalyticsService;
    }

    public FairQuantitativeAnalysisResult quantitative(
            UUID projectId, Instant asOf, UUID riskAssessmentResultId, UUID riskScenarioId) {
        return fairQuantitativeAnalysisService.analyze(projectId, asOf, riskAssessmentResultId, riskScenarioId);
    }

    public FairCamControlAnalyticsResult camControlAnalytics(UUID projectId, Instant asOf, UUID controlId) {
        return fairCamControlAnalyticsService.analyze(projectId, asOf, controlId);
    }
}
