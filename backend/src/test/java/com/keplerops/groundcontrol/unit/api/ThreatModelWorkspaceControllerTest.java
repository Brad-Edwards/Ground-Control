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

import com.keplerops.groundcontrol.api.threatmodels.ThreatModelWorkspaceController;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelWorkspaceResult;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelWorkspaceService;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelStatus;
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
 * @WebMvcTest slice for ThreatModelWorkspaceController — required for Sonar coverage.
 * Mirrors GrcAnalysisControllerTest style.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ThreatModelWorkspaceController.class)
class ThreatModelWorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ThreatModelWorkspaceService workspaceService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID TM_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
    }

    private ThreatModelWorkspaceResult emptyResult() {
        return new ThreatModelWorkspaceResult(List.of(), List.of(), List.of());
    }

    private ThreatModelWorkspaceResult composedResult() {
        var asset = new ThreatModelWorkspaceResult.WorkspaceAsset(
                ASSET_ID, "A-001", "Auth Service", AssetType.SERVICE, false);
        var flow = new ThreatModelWorkspaceResult.WorkspaceFlow(
                UUID.randomUUID(), ASSET_ID, UUID.randomUUID(), AssetRelationType.DATA_FLOW);
        var entry = new ThreatModelWorkspaceResult.WorkspaceThreatEntry(
                TM_ID,
                "TM-001",
                "Credential stuffing",
                ThreatModelStatus.ACTIVE,
                StrideCategory.SPOOFING,
                List.of(ASSET_ID),
                List.of(new ThreatModelWorkspaceResult.WorkspaceLink(
                        UUID.randomUUID(), "CTL-001", "MFA Control", "https://example.com")),
                List.of(new ThreatModelWorkspaceResult.WorkspaceLink(
                        UUID.randomUUID(), "GC-H001", "Security Req", "https://example.com")),
                "FRESH");
        return new ThreatModelWorkspaceResult(List.of(asset), List.of(flow), List.of(entry));
    }

    @Nested
    class HappyPath {

        @Test
        void returns200WithComposedBody() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any()))
                    .thenReturn(composedResult());

            mockMvc.perform(get("/api/v1/threat-models/workspace").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assets", hasSize(1)))
                    .andExpect(jsonPath("$.assets[0].uid", is("A-001")))
                    .andExpect(jsonPath("$.assets[0].boundary", is(false)))
                    .andExpect(jsonPath("$.flows", hasSize(1)))
                    .andExpect(jsonPath("$.entries", hasSize(1)))
                    .andExpect(jsonPath("$.entries[0].uid", is("TM-001")))
                    .andExpect(jsonPath("$.entries[0].status", is("ACTIVE")))
                    .andExpect(jsonPath("$.entries[0].stride", is("SPOOFING")))
                    .andExpect(jsonPath("$.entries[0].staleIndicator", is("FRESH")))
                    .andExpect(jsonPath("$.entries[0].linkedControls", hasSize(1)))
                    .andExpect(jsonPath("$.entries[0].linkedRequirements", hasSize(1)))
                    .andExpect(jsonPath("$.assetCount", is(1)))
                    .andExpect(jsonPath("$.flowCount", is(1)))
                    .andExpect(jsonPath("$.entryCount", is(1)));
        }

        @Test
        void returns200ForEmptyProject() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any()))
                    .thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/threat-models/workspace").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assets", hasSize(0)))
                    .andExpect(jsonPath("$.entries", hasSize(0)))
                    .andExpect(jsonPath("$.assetCount", is(0)));
        }

        @Test
        void passesProjectParamThroughToService() throws Exception {
            when(workspaceService.workspace(eq(PROJECT_ID), any(), anyInt(), any(), any(), any()))
                    .thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/threat-models/workspace").param("project", "ground-control"))
                    .andExpect(status().isOk());

            org.mockito.Mockito.verify(projectService).resolveProjectId("ground-control");
            org.mockito.Mockito.verify(workspaceService)
                    .workspace(eq(PROJECT_ID), any(), anyInt(), isNull(), isNull(), isNull());
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void returns404WhenProjectNotFound() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/threat-models/workspace").param("project", "no-such-project"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns400WhenFreshnessWindowIsZero() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any()))
                    .thenThrow(new DomainValidationException(
                            "freshnessWindowDays must be positive",
                            "validation_error",
                            java.util.Map.of("parameter", "freshnessWindowDays")));

            mockMvc.perform(get("/api/v1/threat-models/workspace")
                            .param("project", "ground-control")
                            .param("freshnessWindowDays", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenStrideEnumInvalid() throws Exception {
            mockMvc.perform(get("/api/v1/threat-models/workspace")
                            .param("project", "ground-control")
                            .param("stride", "INVALID_STRIDE"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenStatusEnumInvalid() throws Exception {
            mockMvc.perform(get("/api/v1/threat-models/workspace")
                            .param("project", "ground-control")
                            .param("status", "INVALID_STATUS"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenFreshnessWindowNegative() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any()))
                    .thenThrow(new DomainValidationException(
                            "freshnessWindowDays must be positive",
                            "validation_error",
                            java.util.Map.of("parameter", "freshnessWindowDays")));

            mockMvc.perform(get("/api/v1/threat-models/workspace")
                            .param("project", "ground-control")
                            .param("freshnessWindowDays", "-5"))
                    .andExpect(status().isBadRequest());
        }
    }
}
