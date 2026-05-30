package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.grcanalysis.GrcAnalysisController;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.GrcAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.NistAssessmentResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionMode;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ObservationProjectionResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.PortfolioSummaryResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.VendorRiskAggregationResult;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistImpactBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistLikelihoodBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatEventKind;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatSourceRelevance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(GrcAnalysisController.class)
class GrcAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GrcAnalysisService grcAnalysisService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
    }

    @Nested
    class EvidenceFreshness {

        @Test
        void happyPath_returns200WithStructuredFields() throws Exception {
            var inputs = new EvidenceFreshnessResult.Inputs(
                    "ground-control", Instant.parse("2026-05-18T00:00:00Z"), 90, false, null, null);
            var counts = new EvidenceFreshnessResult.EvidenceFreshnessCounts(2, 1, 0, 0, 3);
            var result = new EvidenceFreshnessResult(
                    "evidence_freshness",
                    "ground-control",
                    Instant.parse("2026-05-18T00:00:00Z"),
                    "evidence-freshness-projection-v1",
                    inputs,
                    List.of(),
                    List.of(),
                    List.of(),
                    counts,
                    List.of("note"));
            when(grcAnalysisService.evidenceFreshness(eq(PROJECT_ID), any(), anyInt(), anyBoolean(), any(), any()))
                    .thenReturn(result);

            mockMvc.perform(get("/api/v1/analysis/grc/evidence-freshness").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analysisKind", is("evidence_freshness")))
                    .andExpect(jsonPath("$.project", is("ground-control")))
                    .andExpect(jsonPath("$.derivationMethod", is("evidence-freshness-projection-v1")))
                    .andExpect(jsonPath("$.counts.fresh", is(2)))
                    .andExpect(jsonPath("$.counts.stale", is(1)))
                    .andExpect(jsonPath("$.limitations", hasSize(1)));
        }

        @Test
        void projectNotFound_returns404() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/analysis/grc/evidence-freshness").param("project", "missing"))
                    .andExpect(status().isNotFound());
        }

        /** Finding #8: freshnessWindowDays=0 must return 400. */
        @Test
        void zeroFreshnessWindow_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/analysis/grc/evidence-freshness")
                            .param("project", "ground-control")
                            .param("freshnessWindowDays", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void negativeFreshnessWindow_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/analysis/grc/evidence-freshness")
                            .param("project", "ground-control")
                            .param("freshnessWindowDays", "-30"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class ObservationProjection {

        @Test
        void happyPath_returns200WithModeSwitch() throws Exception {
            var inputs = new ObservationProjectionResult.Inputs(
                    "ground-control",
                    Instant.parse("2026-05-18T00:00:00Z"),
                    ObservationProjectionMode.ASSET_EXPOSURE,
                    null,
                    null);
            var result = new ObservationProjectionResult(
                    "observation_exposure",
                    "ground-control",
                    Instant.parse("2026-05-18T00:00:00Z"),
                    "observation-current-state-projection-v1",
                    inputs,
                    List.of(),
                    List.of(),
                    List.of());
            when(grcAnalysisService.observationProjection(
                            eq(PROJECT_ID), any(), eq(ObservationProjectionMode.ASSET_EXPOSURE), any(), any()))
                    .thenReturn(result);

            mockMvc.perform(get("/api/v1/analysis/grc/observation-projection")
                            .param("project", "ground-control")
                            .param("mode", "ASSET_EXPOSURE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analysisKind", is("observation_exposure")))
                    .andExpect(jsonPath("$.inputs.mode", is("ASSET_EXPOSURE")));
        }

        @Test
        void missingMode_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/analysis/grc/observation-projection").param("project", "ground-control"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void invalidMode_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/analysis/grc/observation-projection")
                            .param("project", "ground-control")
                            .param("mode", "BOGUS"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void projectNotFound_returns404() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/analysis/grc/observation-projection")
                            .param("project", "missing")
                            .param("mode", "ASSET_EXPOSURE"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class VendorRisk {

        @Test
        void happyPath_returns200WithThirdPartyLabel() throws Exception {
            var inputs = new VendorRiskAggregationResult.Inputs(
                    "ground-control", Instant.parse("2026-05-18T00:00:00Z"), 90, null);
            var result = new VendorRiskAggregationResult(
                    "vendor_risk_aggregation",
                    "ground-control",
                    Instant.parse("2026-05-18T00:00:00Z"),
                    "vendor-third-party-rollup-v1",
                    inputs,
                    "THIRD_PARTY",
                    List.of(),
                    List.of(
                            "not a first-class vendor aggregate; modeled as OperationalAsset.THIRD_PARTY per GC-L009 carve-out"));
            when(grcAnalysisService.vendorRisk(eq(PROJECT_ID), any(), anyInt(), any()))
                    .thenReturn(result);

            mockMvc.perform(get("/api/v1/analysis/grc/vendor-risk").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analysisKind", is("vendor_risk_aggregation")))
                    .andExpect(jsonPath("$.assetType", is("THIRD_PARTY")))
                    .andExpect(jsonPath("$.derivationMethod", is("vendor-third-party-rollup-v1")))
                    .andExpect(jsonPath("$.limitations", hasSize(1)));
        }

        @Test
        void projectNotFound_returns404() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/analysis/grc/vendor-risk").param("project", "missing"))
                    .andExpect(status().isNotFound());
        }

        /** Finding #8: freshnessWindowDays=0 must return 400. */
        @Test
        void zeroFreshnessWindow_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/analysis/grc/vendor-risk")
                            .param("project", "ground-control")
                            .param("freshnessWindowDays", "0"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class NistAssessment {

        private NistAssessmentResult.NistAssessmentItem sampleItem() {
            var inputs = new NistAssessmentResult.Inputs(
                    java.util.Map.of("id", "TS-1", "name", "External attacker", "kind", "ADVERSARIAL"),
                    java.util.Map.of("id", "TE-1", "description", "Phishing", "kind", "ADVERSARIAL"),
                    ThreatEventKind.ADVERSARIAL,
                    List.of(),
                    List.of(),
                    ThreatSourceRelevance.EXPECTED,
                    NistLikelihoodBand.HIGH,
                    NistLikelihoodBand.MODERATE,
                    NistLikelihoodBand.MODERATE,
                    NistImpactBand.HIGH,
                    java.util.Map.of("from", "2026-01-01", "to", "2026-12-31"));
            var outputs = new NistAssessmentResult.Outputs(
                    NistLikelihoodBand.MODERATE,
                    NistImpactBand.HIGH,
                    "HIGH",
                    "L3-I4",
                    "derived: min(...) per NIST SP 800-30 Rev. 1 Table G-5");
            return new NistAssessmentResult.NistAssessmentItem(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "NIST_SP800_30_R1",
                    "NIST_SP800_30_R1",
                    "1",
                    Instant.parse("2026-05-29T00:00:00Z"),
                    "12 months",
                    "analyst@example",
                    "DRAFT",
                    inputs,
                    outputs,
                    List.of(),
                    List.of("predisposing-condition coverage incomplete"));
        }

        private NistAssessmentResult sampleResult(List<NistAssessmentResult.NistAssessmentItem> items) {
            return new NistAssessmentResult(
                    "nist_assessment",
                    "ground-control",
                    Instant.parse("2026-05-29T00:00:00Z"),
                    "nist-sp800-30-rev1-5x5-matrix-v1",
                    "ordinal",
                    "qualitative ordinal levels",
                    "overall_likelihood × impact_level → risk_level per NIST SP 800-30 Rev. 1 Table I-2",
                    items,
                    new NistAssessmentResult.Counts(items.size(), java.util.Map.of("HIGH", items.size()), items.size()),
                    List.of());
        }

        @Test
        void happyPath_returns200WithMethodologyAttribution() throws Exception {
            when(grcAnalysisService.nistAssessment(eq(PROJECT_ID), any(), any(), any()))
                    .thenReturn(sampleResult(List.of(sampleItem())));

            mockMvc.perform(get("/api/v1/analysis/grc/nist-sp-800-30").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analysisKind", is("nist_assessment")))
                    .andExpect(jsonPath("$.project", is("ground-control")))
                    .andExpect(jsonPath("$.derivationMethod", is("nist-sp800-30-rev1-5x5-matrix-v1")))
                    .andExpect(jsonPath("$.scale", is("ordinal")))
                    .andExpect(jsonPath("$.units", is("qualitative ordinal levels")))
                    .andExpect(jsonPath(
                            "$.matrixConversionRule",
                            is("overall_likelihood × impact_level → risk_level per NIST SP 800-30 Rev. 1 Table I-2")))
                    .andExpect(jsonPath("$.assessments", hasSize(1)))
                    .andExpect(jsonPath("$.assessments[0].profileKey", is("NIST_SP800_30_R1")))
                    .andExpect(jsonPath("$.assessments[0].family", is("NIST_SP800_30_R1")))
                    .andExpect(jsonPath("$.assessments[0].inputs.threatEventKind", is("ADVERSARIAL")))
                    .andExpect(jsonPath("$.assessments[0].inputs.threatSourceRelevance", is("EXPECTED")))
                    .andExpect(jsonPath("$.assessments[0].inputs.likelihoodInitiation", is("HIGH")))
                    .andExpect(jsonPath("$.assessments[0].inputs.likelihoodAdverseImpact", is("MODERATE")))
                    .andExpect(jsonPath("$.assessments[0].inputs.likelihoodOverall", is("MODERATE")))
                    .andExpect(jsonPath("$.assessments[0].inputs.impactLevel", is("HIGH")))
                    .andExpect(jsonPath("$.assessments[0].outputs.overallLikelihood", is("MODERATE")))
                    .andExpect(jsonPath("$.assessments[0].outputs.impactLevel", is("HIGH")))
                    .andExpect(jsonPath("$.assessments[0].outputs.riskLevel", is("HIGH")))
                    .andExpect(jsonPath("$.assessments[0].outputs.matrixCell", is("L3-I4")))
                    .andExpect(jsonPath("$.counts.total", is(1)))
                    .andExpect(jsonPath("$.limitations").isArray());
        }

        @Test
        void emptyResult_returns200WithEmptyAssessmentsArray() throws Exception {
            when(grcAnalysisService.nistAssessment(eq(PROJECT_ID), any(), any(), any()))
                    .thenReturn(sampleResult(List.of()));

            mockMvc.perform(get("/api/v1/analysis/grc/nist-sp-800-30").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assessments", hasSize(0)))
                    .andExpect(jsonPath("$.counts.total", is(0)));
        }

        @Test
        void projectNotFound_returns404() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/analysis/grc/nist-sp-800-30").param("project", "missing"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void invalidRiskAssessmentResultUuid_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/analysis/grc/nist-sp-800-30")
                            .param("project", "ground-control")
                            .param("riskAssessmentResultId", "not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void nonNistProfile_throwsValidationError_returns422() throws Exception {
            when(grcAnalysisService.nistAssessment(eq(PROJECT_ID), any(), any(), any()))
                    .thenThrow(new DomainValidationException(
                            "Risk assessment result is not bound to a NIST_SP800_30_R1 methodology profile"));

            mockMvc.perform(get("/api/v1/analysis/grc/nist-sp-800-30")
                            .param("project", "ground-control")
                            .param("riskAssessmentResultId", UUID.randomUUID().toString()))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    class Portfolio {

        private PortfolioSummaryResult portfolioResult() {
            return new PortfolioSummaryResult(
                    "ground-control",
                    Instant.parse("2026-05-18T00:00:00Z"),
                    "portfolio-projection-v1",
                    new PortfolioSummaryResult.RiskPosture(
                            2,
                            java.util.Map.of("ACTIVE", 2),
                            1,
                            java.util.Map.of("APPROVED", 1),
                            1,
                            java.util.Map.of("PLANNED", 1),
                            java.util.Map.of("MITIGATE", 1),
                            1,
                            java.util.Map.of("IDENTIFIED", 1),
                            1,
                            1,
                            List.of("RRR-009")),
                    new PortfolioSummaryResult.ControlHealth(
                            3,
                            java.util.Map.of("OPERATIONAL", 3),
                            java.util.Map.of("EFFECTIVE", 2),
                            java.util.Map.of("EFFECTIVE", 2),
                            1,
                            1,
                            List.of("CTL-008"),
                            List.of("CTL-009")),
                    new PortfolioSummaryResult.EvidenceFreshness(2, 1, 0, 0, 3),
                    new PortfolioSummaryResult.FindingTrends(
                            4,
                            java.util.Map.of("HIGH", 2),
                            java.util.Map.of("OPEN", 3),
                            java.util.Map.of("CONTROL_DEFICIENCY", 4),
                            3,
                            1,
                            List.of("FIND-001", "FIND-002", "FIND-003"),
                            List.of("FIND-004")),
                    new PortfolioSummaryResult.AssetCriticality(
                            5,
                            java.util.Map.of("CRITICAL", 2),
                            java.util.Map.of("PRODUCTION", 5),
                            java.util.Map.of("IN_SCOPE", 5),
                            List.of("A-001", "A-002")),
                    List.of(new PortfolioSummaryResult.MethodologySummary("FAIR", 1, 1, 1, 1)),
                    List.of("note"));
        }

        @Test
        void happyPath_returns200WithAllDimensions() throws Exception {
            when(grcAnalysisService.portfolio(eq(PROJECT_ID), any(), anyInt())).thenReturn(portfolioResult());

            mockMvc.perform(get("/api/v1/analysis/grc/portfolio").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.project", is("ground-control")))
                    .andExpect(jsonPath("$.riskPosture.totalScenarios", is(2)))
                    .andExpect(jsonPath("$.riskPosture.reassessmentSignals", is(1)))
                    .andExpect(jsonPath("$.controlHealth.totalControls", is(3)))
                    .andExpect(jsonPath("$.controlHealth.unmappedControls", is(1)))
                    .andExpect(jsonPath("$.controlHealth.unmappedControlUids", hasSize(1)))
                    .andExpect(jsonPath("$.controlHealth.unassessedControlUids", hasSize(1)))
                    .andExpect(jsonPath("$.riskPosture.overdueRegisterRecordUids", hasSize(1)))
                    .andExpect(jsonPath("$.evidenceFreshness.currentlyValid", is(3)))
                    .andExpect(jsonPath("$.findingTrends.openCount", is(3)))
                    .andExpect(jsonPath("$.findingTrends.openFindingUids", hasSize(3)))
                    .andExpect(jsonPath("$.findingTrends.overdueFindingUids", hasSize(1)))
                    .andExpect(jsonPath("$.findingTrends.bySeverity.HIGH", is(2)))
                    .andExpect(jsonPath("$.assetCriticality.byCriticality.CRITICAL", is(2)))
                    .andExpect(jsonPath("$.methodologySummaries[0].family", is("FAIR")));
        }

        @Test
        void invalidFreshnessWindow_returns400() throws Exception {
            when(grcAnalysisService.portfolio(eq(PROJECT_ID), any(), anyInt()))
                    .thenThrow(new DomainValidationException("freshnessWindowDays must be positive"));

            mockMvc.perform(get("/api/v1/analysis/grc/portfolio")
                            .param("project", "ground-control")
                            .param("freshnessWindowDays", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void projectNotFound_returns404() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/analysis/grc/portfolio").param("project", "no-such-project"))
                    .andExpect(status().isNotFound());
        }
    }
}
