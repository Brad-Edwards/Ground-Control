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

import com.keplerops.groundcontrol.api.evidence.EvidenceExplorerController;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceExplorerResult;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceExplorerService;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import com.keplerops.groundcontrol.domain.verification.state.AssuranceLevel;
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

/**
 * WebMvcTest slice for EvidenceExplorerController — required for Sonar coverage (GC-Q012).
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(EvidenceExplorerController.class)
class EvidenceExplorerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvidenceExplorerService explorerService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
    }

    private EvidenceExplorerResult emptyResult() {
        return new EvidenceExplorerResult(
                List.of(), List.of(), new EvidenceExplorerResult.FreshnessCounts(0, 0, 0, 0, 0), List.of());
    }

    private EvidenceExplorerResult composedResult() {
        var source = new EvidenceExplorerResult.ExplorerSource(
                EvidenceSourceKind.OBSERVATION, UUID.randomUUID(), null, "primary");
        var finding = new EvidenceExplorerResult.ExplorerFindingRef(
                UUID.randomUUID(), "FIND-001", "Downstream finding", FindingSeverity.HIGH, FindingStatus.OPEN);
        var assessmentRef = new EvidenceExplorerResult.ExplorerAssessmentRef(
                UUID.randomUUID(), UUID.randomUUID(), RiskAssessmentApprovalStatus.APPROVED, "FAIR-CRST");
        var artifact = new EvidenceExplorerResult.ExplorerArtifact(
                UUID.randomUUID(),
                "EV-001",
                "Rollup evidence",
                EvidenceType.OBSERVATION_SUMMARY,
                "ROLLUP",
                NOW,
                "collector",
                AssuranceLevel.L1,
                "HIGH",
                null,
                "FRESH",
                3,
                List.of(source),
                List.of(finding));
        var observation = new EvidenceExplorerResult.ExplorerObservation(
                UUID.randomUUID(),
                ASSET_ID,
                "A-001",
                "CONFIGURATION",
                "os_version",
                "1.2.3",
                "scanner",
                "HIGH",
                "https://example.com/proof",
                NOW,
                null,
                "STALE",
                120,
                List.of(finding),
                List.of(assessmentRef));
        return new EvidenceExplorerResult(
                List.of(artifact),
                List.of(observation),
                new EvidenceExplorerResult.FreshnessCounts(1, 1, 0, 0, 2),
                List.of());
    }

    @Nested
    class HappyPath {

        @Test
        void returns200WithComposedBody() throws Exception {
            when(explorerService.explore(any(), any(), anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(composedResult());

            mockMvc.perform(get("/api/v1/evidence-artifacts/explorer").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evidenceArtifacts", hasSize(1)))
                    .andExpect(jsonPath("$.evidenceArtifacts[0].uid", is("EV-001")))
                    .andExpect(jsonPath("$.evidenceArtifacts[0].evidenceType", is("OBSERVATION_SUMMARY")))
                    .andExpect(jsonPath("$.evidenceArtifacts[0].assuranceLevel", is("L1")))
                    .andExpect(jsonPath("$.evidenceArtifacts[0].freshnessState", is("FRESH")))
                    .andExpect(jsonPath("$.evidenceArtifacts[0].sources", hasSize(1)))
                    .andExpect(jsonPath("$.evidenceArtifacts[0].sources[0].sourceKind", is("OBSERVATION")))
                    .andExpect(jsonPath("$.evidenceArtifacts[0].downstreamFindings", hasSize(1)))
                    .andExpect(jsonPath("$.observations", hasSize(1)))
                    .andExpect(jsonPath("$.observations[0].observationKey", is("os_version")))
                    .andExpect(jsonPath("$.observations[0].observationValue", is("1.2.3")))
                    .andExpect(jsonPath("$.observations[0].freshnessState", is("STALE")))
                    .andExpect(jsonPath("$.observations[0].downstreamFindings", hasSize(1)))
                    .andExpect(jsonPath("$.observations[0].downstreamAssessments", hasSize(1)))
                    .andExpect(jsonPath("$.observations[0].downstreamAssessments[0].approvalState", is("APPROVED")))
                    .andExpect(jsonPath("$.counts.fresh", is(1)))
                    .andExpect(jsonPath("$.counts.currentlyValid", is(2)))
                    .andExpect(jsonPath("$.artifactCount", is(1)))
                    .andExpect(jsonPath("$.observationCount", is(1)));
        }

        @Test
        void passesFiltersThroughToService() throws Exception {
            when(explorerService.explore(
                            eq(PROJECT_ID), any(), anyInt(), eq(ASSET_ID), eq(EvidenceType.ATTESTATION), eq(false)))
                    .thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/evidence-artifacts/explorer")
                            .param("project", "ground-control")
                            .param("assetId", ASSET_ID.toString())
                            .param("evidenceType", "ATTESTATION")
                            .param("includeSuperseded", "false"))
                    .andExpect(status().isOk());

            org.mockito.Mockito.verify(explorerService)
                    .explore(eq(PROJECT_ID), any(), anyInt(), eq(ASSET_ID), eq(EvidenceType.ATTESTATION), eq(false));
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void returns404WhenProjectNotFound() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/evidence-artifacts/explorer").param("project", "no-such-project"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns400WhenEvidenceTypeInvalid() throws Exception {
            mockMvc.perform(get("/api/v1/evidence-artifacts/explorer")
                            .param("project", "ground-control")
                            .param("evidenceType", "NOT_A_TYPE"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns404WhenAssetNotFound() throws Exception {
            when(explorerService.explore(any(), any(), anyInt(), any(), any(), anyBoolean()))
                    .thenThrow(new NotFoundException("Asset not found in project: " + ASSET_ID));

            mockMvc.perform(get("/api/v1/evidence-artifacts/explorer")
                            .param("project", "ground-control")
                            .param("assetId", ASSET_ID.toString()))
                    .andExpect(status().isNotFound());
        }
    }
}
