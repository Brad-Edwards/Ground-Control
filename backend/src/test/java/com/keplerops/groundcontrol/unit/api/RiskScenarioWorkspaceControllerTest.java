package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.riskscenarios.RiskScenarioWorkspaceController;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskScenarioWorkspaceResult;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskScenarioWorkspaceService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskRegisterStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * @WebMvcTest slice for RiskScenarioWorkspaceController — required for Sonar coverage.
 * Mirrors ThreatModelWorkspaceControllerTest style per GC-Q009.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RiskScenarioWorkspaceController.class)
class RiskScenarioWorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskScenarioWorkspaceService workspaceService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID RS_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
    }

    private RiskScenarioWorkspaceResult emptyResult() {
        return new RiskScenarioWorkspaceResult(List.of(), List.of());
    }

    private RiskScenarioWorkspaceResult composedResult() {
        var asset = new RiskScenarioWorkspaceResult.WorkspaceAsset(
                ASSET_ID, "A-001", "Auth Service", AssetType.SERVICE, false);
        var control = new RiskScenarioWorkspaceResult.WorkspaceLink(
                UUID.randomUUID(), "CTL-001", "MFA Control", "https://example.com");
        var finding = new RiskScenarioWorkspaceResult.WorkspaceLink(
                UUID.randomUUID(), "FIND-001", "Finding", "https://example.com");
        var evidence = new RiskScenarioWorkspaceResult.WorkspaceLink(
                UUID.randomUUID(), "EV-001", "Evidence", "https://example.com");
        var req = new RiskScenarioWorkspaceResult.WorkspaceLink(
                UUID.randomUUID(), "GC-Q009", "Req", "https://example.com");
        var assessment = new RiskScenarioWorkspaceResult.WorkspaceAssessment(
                UUID.randomUUID(), "FAIR-CRST", RiskAssessmentApprovalStatus.APPROVED, NOW, "HIGH", null, true);
        var treatment = new RiskScenarioWorkspaceResult.WorkspaceTreatment(
                UUID.randomUUID(),
                "TP-001",
                "Treatment",
                TreatmentStrategy.MITIGATE,
                TreatmentPlanStatus.PLANNED,
                "Alice",
                null);
        var register = new RiskScenarioWorkspaceResult.WorkspaceRegisterRef(
                UUID.randomUUID(), "RRR-001", "Register", RiskRegisterStatus.IDENTIFIED, null);
        var scenario = new RiskScenarioWorkspaceResult.WorkspaceScenario(
                RS_ID,
                "RS-001",
                "Credential stuffing",
                RiskScenarioStatus.ACTIVE,
                "External threat",
                "Stuffing attack",
                "Auth portal",
                "Data breach",
                "12 months",
                "External threat impacts Auth portal via Stuffing attack, causing Data breach",
                List.of(ASSET_ID),
                List.of(control),
                List.of(finding),
                List.of(evidence),
                List.of(req),
                List.of(assessment),
                List.of(treatment),
                List.of(register),
                "CURRENT");
        return new RiskScenarioWorkspaceResult(List.of(scenario), List.of(asset));
    }

    @Nested
    class HappyPath {

        @Test
        void returns200WithComposedBody() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(composedResult());

            mockMvc.perform(get("/api/v1/risk-scenarios/workspace").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scenarios", hasSize(1)))
                    .andExpect(jsonPath("$.scenarios[0].uid", is("RS-001")))
                    .andExpect(jsonPath("$.scenarios[0].status", is("ACTIVE")))
                    .andExpect(jsonPath("$.scenarios[0].fairSentence").exists())
                    .andExpect(jsonPath("$.scenarios[0].linkedAssetIds", hasSize(1)))
                    .andExpect(jsonPath("$.scenarios[0].linkedControls", hasSize(1)))
                    .andExpect(jsonPath("$.scenarios[0].linkedFindings", hasSize(1)))
                    .andExpect(jsonPath("$.scenarios[0].linkedEvidence", hasSize(1)))
                    .andExpect(jsonPath("$.scenarios[0].linkedRequirements", hasSize(1)))
                    .andExpect(jsonPath("$.scenarios[0].assessments", hasSize(1)))
                    .andExpect(jsonPath("$.scenarios[0].assessments[0].methodologyProfileName", is("FAIR-CRST")))
                    .andExpect(jsonPath("$.scenarios[0].assessments[0].hasComputedOutputs", is(true)))
                    .andExpect(jsonPath("$.scenarios[0].treatments", hasSize(1)))
                    .andExpect(jsonPath("$.scenarios[0].treatments[0].uid", is("TP-001")))
                    .andExpect(jsonPath("$.scenarios[0].registerRecords", hasSize(1)))
                    .andExpect(jsonPath("$.scenarios[0].registerRecords[0].uid", is("RRR-001")))
                    .andExpect(jsonPath("$.scenarios[0].reviewIndicator", is("CURRENT")))
                    .andExpect(jsonPath("$.assets", hasSize(1)))
                    .andExpect(jsonPath("$.assets[0].uid", is("A-001")))
                    .andExpect(jsonPath("$.assets[0].boundary", is(false)))
                    .andExpect(jsonPath("$.scenarioCount", is(1)))
                    .andExpect(jsonPath("$.assetCount", is(1)));
        }

        @Test
        void returns200ForEmptyProject() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/risk-scenarios/workspace").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scenarios", hasSize(0)))
                    .andExpect(jsonPath("$.assets", hasSize(0)))
                    .andExpect(jsonPath("$.scenarioCount", is(0)));
        }

        @Test
        void passesProjectParamThroughToService() throws Exception {
            when(workspaceService.workspace(eq(PROJECT_ID), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/risk-scenarios/workspace").param("project", "ground-control"))
                    .andExpect(status().isOk());

            org.mockito.Mockito.verify(projectService).resolveProjectId("ground-control");
            org.mockito.Mockito.verify(workspaceService)
                    .workspace(
                            eq(PROJECT_ID),
                            isNull(),
                            anyInt(),
                            isNull(),
                            isNull(),
                            isNull(),
                            isNull(),
                            isNull(),
                            any());
        }

        @Test
        void compareCsvIsParsedIntoIdList() throws Exception {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/risk-scenarios/workspace")
                            .param("project", "ground-control")
                            .param("compare", id1 + "," + id2))
                    .andExpect(status().isOk());

            var captor = org.mockito.ArgumentCaptor.forClass(List.class);
            org.mockito.Mockito.verify(workspaceService)
                    .workspace(any(), any(), anyInt(), any(), any(), any(), any(), any(), captor.capture());
            @SuppressWarnings("unchecked")
            List<UUID> compareIds = (List<UUID>) captor.getValue();
            org.junit.jupiter.api.Assertions.assertEquals(2, compareIds.size());
            org.junit.jupiter.api.Assertions.assertTrue(compareIds.contains(id1));
            org.junit.jupiter.api.Assertions.assertTrue(compareIds.contains(id2));
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void returns404WhenProjectNotFound() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/risk-scenarios/workspace").param("project", "no-such-project"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns400WhenFreshnessWindowIsZero() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new DomainValidationException(
                            "freshnessWindowDays must be positive",
                            "validation_error",
                            Map.of("parameter", "freshnessWindowDays")));

            mockMvc.perform(get("/api/v1/risk-scenarios/workspace")
                            .param("project", "ground-control")
                            .param("freshnessWindowDays", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenStatusEnumInvalid() throws Exception {
            mockMvc.perform(get("/api/v1/risk-scenarios/workspace")
                            .param("project", "ground-control")
                            .param("status", "INVALID_STATUS"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns404WhenAssetNotFound() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new NotFoundException("Asset not found in project: " + ASSET_ID));

            mockMvc.perform(get("/api/v1/risk-scenarios/workspace")
                            .param("project", "ground-control")
                            .param("assetId", ASSET_ID.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns400WhenFreshnessWindowNegative() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new DomainValidationException(
                            "freshnessWindowDays must be positive",
                            "validation_error",
                            Map.of("parameter", "freshnessWindowDays")));

            mockMvc.perform(get("/api/v1/risk-scenarios/workspace")
                            .param("project", "ground-control")
                            .param("freshnessWindowDays", "-5"))
                    .andExpect(status().isBadRequest());
        }
    }
}
