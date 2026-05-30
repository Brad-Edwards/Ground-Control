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

import com.keplerops.groundcontrol.api.controls.ControlWorkspaceController;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceService;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestMethodology;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.findings.state.FindingType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.LocalDate;
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
 * WebMvcTest slice for ControlWorkspaceController — required for Sonar coverage (GC-Q011).
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ControlWorkspaceController.class)
class ControlWorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ControlWorkspaceService workspaceService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
    }

    private ControlWorkspaceResult emptyResult() {
        return new ControlWorkspaceResult(List.of(), List.of(), List.of());
    }

    private ControlWorkspaceResult composedResult() {
        var asset =
                new ControlWorkspaceResult.WorkspaceAsset(ASSET_ID, "A-001", "Auth Service", AssetType.SERVICE, false);
        var scoped = new ControlWorkspaceResult.WorkspaceScopedImplementation(
                UUID.randomUUID(), "SCI-001", "Prod MFA", ASSET_ID);
        var test = new ControlWorkspaceResult.WorkspaceControlTest(
                UUID.randomUUID(),
                "CT-001",
                ControlTestMethodology.INSPECTION,
                ControlTestConclusion.EFFECTIVE,
                LocalDate.parse("2026-05-01"),
                "Auditor");
        var summary = new ControlWorkspaceResult.WorkspaceTestSummary(
                1, 1, 0, 0, LocalDate.parse("2026-05-01"), ControlTestConclusion.EFFECTIVE);
        var assessment = new ControlWorkspaceResult.WorkspaceAssessment(
                UUID.randomUUID(),
                "CEA-001",
                ControlEffectivenessRating.EFFECTIVE,
                ControlEffectivenessRating.PARTIALLY_EFFECTIVE,
                LocalDate.parse("2026-05-02"),
                "Assessor");
        var exception = new ControlWorkspaceResult.WorkspaceExceptionRef(
                UUID.randomUUID(),
                "FIND-001",
                "Control deficiency",
                FindingType.CONTROL_DEFICIENCY,
                FindingSeverity.HIGH,
                FindingStatus.OPEN);
        var control = new ControlWorkspaceResult.WorkspaceControl(
                CONTROL_ID,
                "CTL-001",
                "MFA on admin portal",
                ControlFunction.PREVENTIVE,
                ControlStatus.OPERATIONAL,
                "Alice",
                "Access Control",
                List.of(scoped),
                List.of(test),
                summary,
                assessment,
                3,
                List.of(exception),
                List.of(ASSET_ID),
                "STALE",
                true);
        var ownerQueue = new ControlWorkspaceResult.OwnerQueue("Alice", 1, 1, List.of("CTL-001"));
        return new ControlWorkspaceResult(List.of(control), List.of(ownerQueue), List.of(asset));
    }

    @Nested
    class HappyPath {

        @Test
        void returns200WithComposedBody() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any()))
                    .thenReturn(composedResult());

            mockMvc.perform(get("/api/v1/controls/workspace").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.controls", hasSize(1)))
                    .andExpect(jsonPath("$.controls[0].uid", is("CTL-001")))
                    .andExpect(jsonPath("$.controls[0].controlFunction", is("PREVENTIVE")))
                    .andExpect(jsonPath("$.controls[0].status", is("OPERATIONAL")))
                    .andExpect(jsonPath("$.controls[0].owner", is("Alice")))
                    .andExpect(jsonPath("$.controls[0].scopedImplementations", hasSize(1)))
                    .andExpect(jsonPath("$.controls[0].tests", hasSize(1)))
                    .andExpect(jsonPath("$.controls[0].tests[0].conclusion", is("EFFECTIVE")))
                    .andExpect(jsonPath("$.controls[0].testSummary.total", is(1)))
                    .andExpect(jsonPath("$.controls[0].latestAssessment.designEffectiveness", is("EFFECTIVE")))
                    .andExpect(jsonPath(
                            "$.controls[0].latestAssessment.operatingEffectiveness", is("PARTIALLY_EFFECTIVE")))
                    .andExpect(jsonPath("$.controls[0].mappingCount", is(3)))
                    .andExpect(jsonPath("$.controls[0].exceptions", hasSize(1)))
                    .andExpect(jsonPath("$.controls[0].exceptions[0].findingType", is("CONTROL_DEFICIENCY")))
                    .andExpect(jsonPath("$.controls[0].staleIndicator", is("STALE")))
                    .andExpect(jsonPath("$.controls[0].needsAttention", is(true)))
                    .andExpect(jsonPath("$.ownerQueues", hasSize(1)))
                    .andExpect(jsonPath("$.ownerQueues[0].owner", is("Alice")))
                    .andExpect(jsonPath("$.ownerQueues[0].attentionControls", is(1)))
                    .andExpect(jsonPath("$.assets", hasSize(1)))
                    .andExpect(jsonPath("$.controlCount", is(1)))
                    .andExpect(jsonPath("$.assetCount", is(1)));
        }

        @Test
        void returns200ForEmptyProject() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any()))
                    .thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/controls/workspace").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.controls", hasSize(0)))
                    .andExpect(jsonPath("$.controlCount", is(0)));
        }

        @Test
        void passesFiltersThroughToService() throws Exception {
            when(workspaceService.workspace(
                            eq(PROJECT_ID),
                            any(),
                            anyInt(),
                            eq(ControlStatus.OPERATIONAL),
                            eq(ControlFunction.PREVENTIVE),
                            eq("Alice"),
                            eq(ASSET_ID)))
                    .thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/controls/workspace")
                            .param("project", "ground-control")
                            .param("status", "OPERATIONAL")
                            .param("controlFunction", "PREVENTIVE")
                            .param("owner", "Alice")
                            .param("assetId", ASSET_ID.toString()))
                    .andExpect(status().isOk());

            org.mockito.Mockito.verify(projectService).resolveProjectId("ground-control");
            org.mockito.Mockito.verify(workspaceService)
                    .workspace(
                            eq(PROJECT_ID),
                            isNull(),
                            anyInt(),
                            eq(ControlStatus.OPERATIONAL),
                            eq(ControlFunction.PREVENTIVE),
                            eq("Alice"),
                            eq(ASSET_ID));
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void returns404WhenProjectNotFound() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/controls/workspace").param("project", "no-such-project"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns400WhenStatusEnumInvalid() throws Exception {
            mockMvc.perform(get("/api/v1/controls/workspace")
                            .param("project", "ground-control")
                            .param("status", "NOT_A_STATUS"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenFreshnessWindowNotPositive() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any()))
                    .thenThrow(new DomainValidationException(
                            "freshnessWindowDays must be positive",
                            "validation_error",
                            Map.of("parameter", "freshnessWindowDays")));

            mockMvc.perform(get("/api/v1/controls/workspace")
                            .param("project", "ground-control")
                            .param("freshnessWindowDays", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns404WhenAssetNotFound() throws Exception {
            when(workspaceService.workspace(any(), any(), anyInt(), any(), any(), any(), any()))
                    .thenThrow(new NotFoundException("Asset not found in project: " + ASSET_ID));

            mockMvc.perform(get("/api/v1/controls/workspace")
                            .param("project", "ground-control")
                            .param("assetId", ASSET_ID.toString()))
                    .andExpect(status().isNotFound());
        }
    }
}
