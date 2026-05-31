package com.keplerops.groundcontrol.integration.grcanalysis;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class GrcAnalysisIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void evidenceFreshness_returnsStructuredResultForSeedProject() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/evidence-freshness").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("evidence_freshness")))
                .andExpect(jsonPath("$.project", is("ground-control")))
                .andExpect(jsonPath("$.derivationMethod", is("evidence-freshness-projection-v1")))
                .andExpect(jsonPath("$.inputs.freshnessWindowDays", is(90)))
                .andExpect(jsonPath("$.inputs.includeSuperseded", is(false)))
                .andExpect(jsonPath("$.inputs.asOf").exists())
                // The count assertions below check shape (numeric field present),
                // not value, because the seed corpus does not guarantee a fresh
                // artifact. A regression that drops one of the count fields must
                // still fail this test.
                .andExpect(jsonPath("$.counts.fresh").isNumber())
                .andExpect(jsonPath("$.counts.stale").isNumber())
                .andExpect(jsonPath("$.counts.expired").isNumber())
                .andExpect(jsonPath("$.counts.superseded").isNumber())
                .andExpect(jsonPath("$.evidenceArtifacts").isArray())
                .andExpect(jsonPath("$.observations").isArray())
                .andExpect(jsonPath("$.controlTests").isArray())
                .andExpect(jsonPath("$.limitations").isArray());
    }

    @Test
    void observationProjection_assetExposureMode_returnsStructuredResult() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/observation-projection")
                        .param("project", "ground-control")
                        .param("mode", "ASSET_EXPOSURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("observation_exposure")))
                .andExpect(jsonPath("$.derivationMethod", is("observation-current-state-projection-v1")))
                .andExpect(jsonPath("$.inputs.mode", is("ASSET_EXPOSURE")));
    }

    @Test
    void observationProjection_controlStateMode_carriesAntiPatternLimitation() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/observation-projection")
                        .param("project", "ground-control")
                        .param("mode", "CONTROL_STATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("control_state")))
                .andExpect(jsonPath(
                        "$.limitations[?(@ =~ /.*ControlStatus.OPERATIONAL is NOT treated.*/)]",
                        is(java.util.List.of("controlEffectiveness is derived from ControlEffectivenessAssessment; "
                                + "ControlStatus.OPERATIONAL is NOT treated as evidence of effectiveness "
                                + "(preflight anti-pattern)"))));
    }

    @Test
    void vendorRisk_carriesThirdPartyLabelAndCarveOutLimitation() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/vendor-risk").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("vendor_risk_aggregation")))
                .andExpect(jsonPath("$.assetType", is("THIRD_PARTY")))
                .andExpect(jsonPath("$.derivationMethod", is("vendor-third-party-rollup-v1")))
                .andExpect(jsonPath(
                        "$.limitations[?(@ =~ /.*GC-L009 carve-out.*/)]",
                        is(java.util.List.of(
                                "not a first-class vendor aggregate; modeled as OperationalAsset.THIRD_PARTY"
                                        + " per GC-L009 carve-out"))));
    }

    @Test
    void nistAssessment_returnsMethodologyAttributedEnvelopeForSeedProject() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/nist-sp-800-30").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("nist_assessment")))
                .andExpect(jsonPath("$.project", is("ground-control")))
                .andExpect(jsonPath("$.derivationMethod", is("nist-sp800-30-rev1-5x5-matrix-v1")))
                .andExpect(jsonPath("$.scale", is("ordinal")))
                .andExpect(jsonPath("$.units", is("qualitative ordinal levels")))
                .andExpect(jsonPath("$.matrixConversionRule").exists())
                .andExpect(jsonPath("$.assessments").isArray())
                .andExpect(jsonPath("$.counts.total").isNumber())
                .andExpect(jsonPath("$.limitations").isArray());
    }

    @Test
    void riskHeatmap_returnsMethodologyAttributedEnvelopeForSeedProject() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/risk-heatmap").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("risk_heatmap")))
                .andExpect(jsonPath("$.derivationMethod", is("qualitative-likelihood-impact-heatmap-v1")))
                .andExpect(jsonPath("$.scale", is("ordinal")))
                .andExpect(jsonPath("$.cells").isArray())
                .andExpect(jsonPath("$.counts.totalAssessments").isNumber())
                .andExpect(jsonPath("$.limitations").isArray());
    }

    @Test
    void riskDistribution_returnsBucketedCountsForSeedProject() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/risk-distribution")
                        .param("project", "ground-control")
                        .param("groupBy", "STATUS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("risk_distribution")))
                .andExpect(jsonPath("$.derivationMethod", is("risk-register-distribution-v1")))
                .andExpect(jsonPath("$.inputs.groupBy", is("STATUS")))
                .andExpect(jsonPath("$.buckets").isArray());
    }

    @Test
    void riskTopN_returnsDefaultLimitForSeedProject() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/risk-top-n").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("risk_top_n")))
                .andExpect(jsonPath("$.derivationMethod", is("latest-per-scenario-top-n-v1")))
                .andExpect(jsonPath("$.inputs.limit", is(10)))
                .andExpect(jsonPath("$.inputs.orderBy", is("CURRENT_ASSESSMENT_OUTPUT")))
                .andExpect(jsonPath("$.entries").isArray());
    }

    @Test
    void riskTrends_returnsMonthlyBucketsForSeedProject() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/risk-trends").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("risk_trends")))
                .andExpect(jsonPath("$.derivationMethod", is("risk-register-envers-audit-trends-v1")))
                .andExpect(jsonPath("$.inputs.bucket", is("MONTH")))
                .andExpect(jsonPath("$.inputs.entity", is("RiskRegisterRecord")))
                .andExpect(jsonPath("$.points").isArray());
    }

    @Test
    void riskPosture_returnsAppetiteDeferralLimitationForSeedProject() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/grc/risk-posture").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("risk_posture")))
                .andExpect(jsonPath("$.derivationMethod", is("risk-register-and-approval-state-rollup-v1")))
                .andExpect(jsonPath("$.statusSummary.totalRecords").isNumber())
                .andExpect(jsonPath(
                        "$.limitations[?(@ =~ /.*RiskAppetiteEvaluator.*/)]",
                        is(java.util.List.of(
                                "appetite/tolerance evaluation deferred to the shared RiskAppetiteEvaluator"
                                        + " kernel from cluster 1 (GC-T005); posture summary reports status /"
                                        + " approval-state distributions only — do not interpret as appetite-conforming"
                                        + " posture"))));
    }
}
