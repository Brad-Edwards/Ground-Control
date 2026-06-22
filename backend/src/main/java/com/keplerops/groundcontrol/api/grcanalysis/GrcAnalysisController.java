package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlAnalyticsQuery;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlDomain;
import com.keplerops.groundcontrol.domain.grcanalysis.service.GrcAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionMode;
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
 */
@RestController
@RequestMapping("/api/v1/analysis/grc")
@Validated
public class GrcAnalysisController {

    private static final int DEFAULT_FRESHNESS_WINDOW_DAYS = 90;

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

    @GetMapping("/fair-quantitative")
    public FairQuantitativeAnalysisResponse fairQuantitative(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) UUID riskAssessmentResultId,
            @RequestParam(required = false) UUID riskScenarioId) {
        UUID projectId = projectService.resolveProjectId(project);
        return FairQuantitativeAnalysisResponse.from(
                grcAnalysisService.fairQuantitative(projectId, asOf, riskAssessmentResultId, riskScenarioId));
    }

    @GetMapping("/compliance-monitoring")
    public ComplianceMonitoringResponse complianceMonitoring(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays) {
        UUID projectId = projectService.resolveProjectId(project);
        return ComplianceMonitoringResponse.from(
                grcAnalysisService.complianceMonitoring(projectId, asOf, freshnessWindowDays));
    }

    @GetMapping("/fair-cam-control-analytics")
    public FairCamControlAnalyticsResponse fairCamControlAnalytics(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) UUID controlId,
            @RequestParam(required = false) UUID scopedImplementationId,
            @RequestParam(required = false) UUID riskScenarioId,
            @RequestParam(required = false) UUID riskRegisterRecordId,
            @RequestParam(required = false) UUID threatModelId,
            @RequestParam(required = false) UUID methodologyProfileId,
            @RequestParam(required = false) FairCamControlDomain domain,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays) {
        UUID projectId = projectService.resolveProjectId(project);
        FairCamControlAnalyticsQuery query = new FairCamControlAnalyticsQuery(
                asOf,
                freshnessWindowDays,
                controlId,
                scopedImplementationId,
                riskScenarioId,
                riskRegisterRecordId,
                threatModelId,
                methodologyProfileId,
                domain);
        return FairCamControlAnalyticsResponse.from(grcAnalysisService.fairCamControlAnalytics(projectId, query));
    }
}
