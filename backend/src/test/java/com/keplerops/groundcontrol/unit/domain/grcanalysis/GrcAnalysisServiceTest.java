package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlAnalyticsResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlAnalyticsService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairQuantitativeAnalysisResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairQuantitativeAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.GrcAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.NistAssessmentResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.NistAssessmentService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionMode;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskAnalysisOrchestrator;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskDistributionGroupBy;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskDistributionResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskHeatmapResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskPostureResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTopNOrderBy;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTopNResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsBucket;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.VendorRiskAggregationResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.VendorRiskAggregationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the orchestrator. Each public method must delegate to the
 * matching analysis service with the same arguments and return whatever the
 * underlying service returns, unchanged.
 */
@ExtendWith(MockitoExtension.class)
class GrcAnalysisServiceTest {

    @Mock
    private EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    @Mock
    private ObservationProjectionService observationProjectionService;

    @Mock
    private VendorRiskAggregationService vendorRiskAggregationService;

    @Mock
    private NistAssessmentService nistAssessmentService;

    @Mock
    private RiskAnalysisOrchestrator riskAnalysisOrchestrator;

    @Mock
    private FairQuantitativeAnalysisService fairQuantitativeAnalysisService;

    @Mock
    private FairCamControlAnalyticsService fairCamControlAnalyticsService;

    @InjectMocks
    private GrcAnalysisService service;

    @Test
    void evidenceFreshness_delegatesToEvidenceFreshnessAnalysisService() {
        UUID projectId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID controlId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-18T00:00:00Z");
        EvidenceFreshnessResult expected = new EvidenceFreshnessResult(
                "evidence_freshness",
                "ground-control",
                asOf,
                "evidence-freshness-projection-v1",
                new EvidenceFreshnessResult.Inputs("ground-control", asOf, 90, true, assetId, controlId),
                List.of(),
                List.of(),
                List.of(),
                new EvidenceFreshnessResult.EvidenceFreshnessCounts(0, 0, 0, 0, 0),
                List.of());
        when(evidenceFreshnessAnalysisService.analyze(projectId, asOf, 90, true, assetId, controlId))
                .thenReturn(expected);

        EvidenceFreshnessResult actual = service.evidenceFreshness(projectId, asOf, 90, true, assetId, controlId);

        assertThat(actual).isSameAs(expected);
        verify(evidenceFreshnessAnalysisService).analyze(projectId, asOf, 90, true, assetId, controlId);
    }

    @Test
    void observationProjection_delegatesToObservationProjectionService() {
        UUID projectId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID controlId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-18T00:00:00Z");
        ObservationProjectionResult expected = new ObservationProjectionResult(
                "observation_exposure",
                "ground-control",
                asOf,
                "observation-current-state-projection-v1",
                new ObservationProjectionResult.Inputs(
                        "ground-control", asOf, ObservationProjectionMode.ASSET_EXPOSURE, assetId, controlId),
                List.of(),
                List.of(),
                List.of());
        when(observationProjectionService.project(
                        projectId, asOf, ObservationProjectionMode.ASSET_EXPOSURE, assetId, controlId))
                .thenReturn(expected);

        ObservationProjectionResult actual = service.observationProjection(
                projectId, asOf, ObservationProjectionMode.ASSET_EXPOSURE, assetId, controlId);

        assertThat(actual).isSameAs(expected);
        verify(observationProjectionService)
                .project(projectId, asOf, ObservationProjectionMode.ASSET_EXPOSURE, assetId, controlId);
    }

    @Test
    void vendorRisk_delegatesToVendorRiskAggregationService() {
        UUID projectId = UUID.randomUUID();
        UUID vendorAssetId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-18T00:00:00Z");
        VendorRiskAggregationResult expected = new VendorRiskAggregationResult(
                "vendor_risk_aggregation",
                "ground-control",
                asOf,
                "vendor-third-party-rollup-v1",
                new VendorRiskAggregationResult.Inputs("ground-control", asOf, 90, vendorAssetId),
                "THIRD_PARTY",
                List.of(),
                List.of());
        when(vendorRiskAggregationService.aggregate(projectId, asOf, 90, vendorAssetId))
                .thenReturn(expected);

        VendorRiskAggregationResult actual = service.vendorRisk(projectId, asOf, 90, vendorAssetId);

        assertThat(actual).isSameAs(expected);
        verify(vendorRiskAggregationService).aggregate(projectId, asOf, 90, vendorAssetId);
    }

    @Test
    void nistAssessment_delegatesToNistAssessmentService() {
        UUID projectId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-29T00:00:00Z");
        NistAssessmentResult expected = new NistAssessmentResult(
                "nist_assessment",
                "ground-control",
                asOf,
                "nist-sp800-30-rev1-5x5-matrix-v1",
                "ordinal",
                "qualitative ordinal levels",
                "rule",
                List.of(),
                new NistAssessmentResult.Counts(0, Map.of(), 0),
                List.of());
        when(nistAssessmentService.analyze(projectId, asOf, assessmentId, scenarioId))
                .thenReturn(expected);

        NistAssessmentResult actual = service.nistAssessment(projectId, asOf, assessmentId, scenarioId);

        assertThat(actual).isSameAs(expected);
        verify(nistAssessmentService).analyze(projectId, asOf, assessmentId, scenarioId);
    }

    @Test
    void riskHeatmap_delegatesToRiskAnalysisOrchestrator() {
        UUID projectId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-30T00:00:00Z");
        RiskHeatmapResult expected = new RiskHeatmapResult(
                "risk_heatmap",
                "ground-control",
                asOf,
                "qualitative-likelihood-impact-heatmap-v1",
                profileId,
                "NIST_SP800_30_R1",
                "ordinal",
                "qualitative ordinal levels",
                new RiskHeatmapResult.Inputs("ground-control", asOf, profileId),
                List.of(),
                new RiskHeatmapResult.Counts(0, 0, 0, Map.of()),
                List.of());
        when(riskAnalysisOrchestrator.heatmap(projectId, asOf, profileId)).thenReturn(expected);

        RiskHeatmapResult actual = service.riskHeatmap(projectId, asOf, profileId);

        assertThat(actual).isSameAs(expected);
        verify(riskAnalysisOrchestrator).heatmap(projectId, asOf, profileId);
    }

    @Test
    void riskDistribution_delegatesToRiskAnalysisOrchestrator() {
        UUID projectId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-30T00:00:00Z");
        RiskDistributionResult expected = new RiskDistributionResult(
                "risk_distribution",
                "ground-control",
                asOf,
                "risk-register-distribution-v1",
                "nominal",
                "register record counts",
                new RiskDistributionResult.Inputs("ground-control", asOf, "STATUS"),
                List.of(),
                new RiskDistributionResult.Counts(0, 0, 0, Map.of()),
                List.of());
        when(riskAnalysisOrchestrator.distribution(projectId, asOf, RiskDistributionGroupBy.STATUS))
                .thenReturn(expected);

        RiskDistributionResult actual = service.riskDistribution(projectId, asOf, RiskDistributionGroupBy.STATUS);

        assertThat(actual).isSameAs(expected);
        verify(riskAnalysisOrchestrator).distribution(projectId, asOf, RiskDistributionGroupBy.STATUS);
    }

    @Test
    void riskTopN_delegatesToRiskAnalysisOrchestrator() {
        UUID projectId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-30T00:00:00Z");
        RiskTopNResult expected = new RiskTopNResult(
                "risk_top_n",
                "ground-control",
                asOf,
                "latest-per-scenario-top-n-v1",
                "methodology-specific",
                "methodology-specific",
                new RiskTopNResult.Inputs("ground-control", asOf, 10, "CURRENT_ASSESSMENT_OUTPUT"),
                List.of(),
                new RiskTopNResult.Counts(0, 0),
                List.of());
        when(riskAnalysisOrchestrator.topN(projectId, asOf, 10, RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT))
                .thenReturn(expected);

        RiskTopNResult actual = service.riskTopN(projectId, asOf, 10, RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT);

        assertThat(actual).isSameAs(expected);
        verify(riskAnalysisOrchestrator).topN(projectId, asOf, 10, RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT);
    }

    @Test
    void riskTrends_delegatesToRiskAnalysisOrchestrator() {
        UUID projectId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-30T00:00:00Z");
        Instant from = Instant.parse("2025-05-30T00:00:00Z");
        Instant to = asOf;
        RiskTrendsResult expected = new RiskTrendsResult(
                "risk_trends",
                "ground-control",
                asOf,
                "risk-register-envers-audit-trends-v1",
                "count",
                "audit revisions per bucket",
                new RiskTrendsResult.Inputs("ground-control", asOf, from, to, "MONTH", "RiskRegisterRecord"),
                List.of(),
                new RiskTrendsResult.Counts(0, 0),
                List.of());
        when(riskAnalysisOrchestrator.trends(projectId, asOf, from, to, RiskTrendsBucket.MONTH))
                .thenReturn(expected);

        RiskTrendsResult actual = service.riskTrends(projectId, asOf, from, to, RiskTrendsBucket.MONTH);

        assertThat(actual).isSameAs(expected);
        verify(riskAnalysisOrchestrator).trends(projectId, asOf, from, to, RiskTrendsBucket.MONTH);
    }

    @Test
    void riskPosture_delegatesToRiskAnalysisOrchestrator() {
        UUID projectId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-30T00:00:00Z");
        RiskPostureResult expected = new RiskPostureResult(
                "risk_posture",
                "ground-control",
                asOf,
                "risk-register-and-approval-state-rollup-v1",
                "count",
                "register records and approval-state counts",
                new RiskPostureResult.Inputs("ground-control", asOf),
                new RiskPostureResult.StatusSummary(0, 0, 0, 0, Map.of()),
                new RiskPostureResult.ApprovalSummary(0, Map.of()),
                new RiskPostureResult.ReassessmentSummary(0, 0),
                List.of());
        when(riskAnalysisOrchestrator.posture(projectId, asOf)).thenReturn(expected);

        RiskPostureResult actual = service.riskPosture(projectId, asOf);

        assertThat(actual).isSameAs(expected);
        verify(riskAnalysisOrchestrator).posture(projectId, asOf);
    }

    @Test
    void fairQuantitativeAnalysis_delegatesToFairQuantitativeAnalysisService() {
        UUID projectId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-30T00:00:00Z");
        FairQuantitativeAnalysisResult expected = new FairQuantitativeAnalysisResult(
                "fair_analysis",
                "ground-control",
                asOf,
                "fair-v3.0-monte-carlo-pert-v1",
                "continuous",
                "monetary per year",
                "USD",
                List.of(),
                new FairQuantitativeAnalysisResult.Counts(0, 0, 0),
                List.of());
        when(fairQuantitativeAnalysisService.analyze(projectId, asOf, assessmentId, scenarioId))
                .thenReturn(expected);

        FairQuantitativeAnalysisResult actual =
                service.fairQuantitativeAnalysis(projectId, asOf, assessmentId, scenarioId);

        assertThat(actual).isSameAs(expected);
        verify(fairQuantitativeAnalysisService).analyze(projectId, asOf, assessmentId, scenarioId);
    }

    @Test
    void fairCamControlAnalytics_delegatesToFairCamControlAnalyticsService() {
        UUID projectId = UUID.randomUUID();
        UUID controlId = UUID.randomUUID();
        Instant asOf = Instant.parse("2026-05-30T00:00:00Z");
        FairCamControlAnalyticsResult expected = new FairCamControlAnalyticsResult(
                "fair_cam_control_analytics",
                "ground-control",
                asOf,
                "fair-cam-v1-domain-attribution-and-three-dimensions",
                "fraction",
                "fraction (0.0–1.0) per FAIR-CAM dimension",
                List.of(),
                new FairCamControlAnalyticsResult.Counts(0, java.util.Map.of(), 0),
                List.of());
        when(fairCamControlAnalyticsService.analyze(projectId, asOf, controlId)).thenReturn(expected);

        FairCamControlAnalyticsResult actual = service.fairCamControlAnalytics(projectId, asOf, controlId);

        assertThat(actual).isSameAs(expected);
        verify(fairCamControlAnalyticsService).analyze(projectId, asOf, controlId);
    }
}
