package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.GrcAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionMode;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskDistributionGroupBy;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTopNOrderBy;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsBucket;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST surface for the GRC analysis tools per GC-L007. Mirrors the
 * existing {@code AnalysisController} pattern in {@code api/admin}: resolve
 * project once at the boundary, delegate to the orchestrator service, and map
 * the domain result to an API response record so the public JSON contract is
 * decoupled from internal service records.
 *
 * <p>Cluster 3 (GC-T008) adds five methodology-attributed projections on top
 * of the existing GC-L007 / GC-T014 endpoints: heat map, distribution, top-N,
 * trends, posture.
 */
@RestController
@RequestMapping("/api/v1/analysis/grc")
@Validated
public class GrcAnalysisController {

    private static final int DEFAULT_FRESHNESS_WINDOW_DAYS = 90;
    private static final int DEFAULT_TOP_N_LIMIT = 10;

    private final GrcAnalysisService grcAnalysisService;
    private final ProjectService projectService;

    public GrcAnalysisController(GrcAnalysisService grcAnalysisService, ProjectService projectService) {
        this.grcAnalysisService = grcAnalysisService;
        this.projectService = projectService;
    }

    @GetMapping("/evidence-freshness")
    public EvidenceFreshnessResponse evidenceFreshness(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays,
            @RequestParam(required = false, defaultValue = "false") boolean includeSuperseded,
            @RequestParam(required = false) UUID assetId,
            @RequestParam(required = false) UUID controlId) {
        UUID projectId = projectService.resolveProjectId(project);
        return EvidenceFreshnessResponse.from(grcAnalysisService.evidenceFreshness(
                projectId, asOf, freshnessWindowDays, includeSuperseded, assetId, controlId));
    }

    @GetMapping("/observation-projection")
    public ObservationProjectionResponse observationProjection(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam ObservationProjectionMode mode,
            @RequestParam(required = false) UUID assetId,
            @RequestParam(required = false) UUID controlId) {
        UUID projectId = projectService.resolveProjectId(project);
        return ObservationProjectionResponse.from(
                grcAnalysisService.observationProjection(projectId, asOf, mode, assetId, controlId));
    }

    @GetMapping("/vendor-risk")
    public VendorRiskAggregationResponse vendorRisk(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays,
            @RequestParam(required = false) UUID vendorAssetId) {
        UUID projectId = projectService.resolveProjectId(project);
        return VendorRiskAggregationResponse.from(
                grcAnalysisService.vendorRisk(projectId, asOf, freshnessWindowDays, vendorAssetId));
    }

    @GetMapping("/nist-sp-800-30")
    public NistAssessmentResponse nistAssessment(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) UUID riskAssessmentResultId,
            @RequestParam(required = false) UUID riskScenarioId) {
        UUID projectId = projectService.resolveProjectId(project);
        return NistAssessmentResponse.from(
                grcAnalysisService.nistAssessment(projectId, asOf, riskAssessmentResultId, riskScenarioId));
    }

    @GetMapping("/portfolio")
    public PortfolioSummaryResponse portfolio(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays) {
        UUID projectId = projectService.resolveProjectId(project);
        return PortfolioSummaryResponse.from(grcAnalysisService.portfolio(projectId, asOf, freshnessWindowDays));
    }

    @GetMapping("/risk-heatmap")
    public RiskHeatmapResponse riskHeatmap(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) UUID methodologyProfileId) {
        UUID projectId = projectService.resolveProjectId(project);
        return RiskHeatmapResponse.from(grcAnalysisService.riskHeatmap(projectId, asOf, methodologyProfileId));
    }

    @GetMapping("/risk-distribution")
    public RiskDistributionResponse riskDistribution(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam RiskDistributionGroupBy groupBy) {
        UUID projectId = projectService.resolveProjectId(project);
        return RiskDistributionResponse.from(grcAnalysisService.riskDistribution(projectId, asOf, groupBy));
    }

    @GetMapping("/risk-top-n")
    public RiskTopNResponse riskTopN(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_TOP_N_LIMIT) @Positive int limit,
            @RequestParam(required = false, defaultValue = "CURRENT_ASSESSMENT_OUTPUT") RiskTopNOrderBy orderBy) {
        UUID projectId = projectService.resolveProjectId(project);
        return RiskTopNResponse.from(grcAnalysisService.riskTopN(projectId, asOf, limit, orderBy));
    }

    @GetMapping("/risk-trends")
    public RiskTrendsResponse riskTrends(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "MONTH") RiskTrendsBucket bucket) {
        UUID projectId = projectService.resolveProjectId(project);
        return RiskTrendsResponse.from(grcAnalysisService.riskTrends(projectId, asOf, from, to, bucket));
    }

    @GetMapping("/risk-posture")
    public RiskPostureResponse riskPosture(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        UUID projectId = projectService.resolveProjectId(project);
        return RiskPostureResponse.from(grcAnalysisService.riskPosture(projectId, asOf));
    }

    @GetMapping("/fair-quantitative")
    public FairQuantitativeAnalysisResponse fairQuantitativeAnalysis(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) UUID riskAssessmentResultId,
            @RequestParam(required = false) UUID riskScenarioId) {
        UUID projectId = projectService.resolveProjectId(project);
        return FairQuantitativeAnalysisResponse.from(
                grcAnalysisService.fairQuantitativeAnalysis(projectId, asOf, riskAssessmentResultId, riskScenarioId));
    }

    @GetMapping("/fair-cam-control-analytics")
    public FairCamControlAnalyticsResponse fairCamControlAnalytics(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) UUID controlId) {
        UUID projectId = projectService.resolveProjectId(project);
        return FairCamControlAnalyticsResponse.from(
                grcAnalysisService.fairCamControlAnalytics(projectId, asOf, controlId));
    }
}
