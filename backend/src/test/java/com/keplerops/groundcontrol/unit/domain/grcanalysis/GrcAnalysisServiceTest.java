package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.GrcAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.NistAssessmentResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.NistAssessmentService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionMode;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.VendorRiskAggregationResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.VendorRiskAggregationService;
import java.time.Instant;
import java.util.List;
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
                new NistAssessmentResult.Counts(0, java.util.Map.of(), 0),
                List.of());
        when(nistAssessmentService.analyze(projectId, asOf, assessmentId, scenarioId))
                .thenReturn(expected);

        NistAssessmentResult actual = service.nistAssessment(projectId, asOf, assessmentId, scenarioId);

        assertThat(actual).isSameAs(expected);
        verify(nistAssessmentService).analyze(projectId, asOf, assessmentId, scenarioId);
    }
}
