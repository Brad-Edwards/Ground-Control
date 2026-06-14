package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.controls.ControlWorkspaceController;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceService;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ControlWorkspaceController.class)
class ControlWorkspaceControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant AS_OF = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ControlWorkspaceService workspaceService;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void workspaceMapsQueryParamsAndReturnsComposedControls() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(workspaceService.workspace(
                        PROJECT_ID,
                        AS_OF,
                        30,
                        ControlStatus.OPERATIONAL,
                        ControlFunction.PREVENTIVE,
                        "alice",
                        "CURRENT"))
                .thenReturn(sampleWorkspace());

        mockMvc.perform(get("/api/v1/controls/workspace")
                        .param("project", "ground-control")
                        .param("asOf", AS_OF.toString())
                        .param("freshnessWindowDays", "30")
                        .param("status", "OPERATIONAL")
                        .param("controlFunction", "PREVENTIVE")
                        .param("owner", "alice")
                        .param("queue", "CURRENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlCount", is(1)))
                .andExpect(jsonPath("$.controls[0].uid", is("CTL-001")))
                .andExpect(jsonPath("$.controls[0].tests[0].uid", is("CTEST-001")))
                .andExpect(jsonPath("$.controls[0].evidence[0].uid", is("EV-001")))
                .andExpect(jsonPath("$.controls[0].findings[0].uid", is("FIND-001")))
                .andExpect(jsonPath("$.controls[0].riskMappings[0].evidenceRefs[0].evidenceRef", is("EVD-REF-001")))
                .andExpect(jsonPath("$.controls[0].queueReasons[0]", is("CURRENT")));

        verify(workspaceService)
                .workspace(
                        PROJECT_ID,
                        AS_OF,
                        30,
                        ControlStatus.OPERATIONAL,
                        ControlFunction.PREVENTIVE,
                        "alice",
                        "CURRENT");
    }

    @Test
    void workspaceUsesDefaultFreshnessWindow() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(workspaceService.workspace(PROJECT_ID, null, 90, null, null, null, null))
                .thenReturn(new ControlWorkspaceResult(List.of()));

        mockMvc.perform(get("/api/v1/controls/workspace").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlCount", is(0)));

        verify(workspaceService).workspace(PROJECT_ID, null, 90, null, null, null, null);
    }

    @Test
    void workspaceRejectsNonPositiveFreshnessWindow() throws Exception {
        mockMvc.perform(get("/api/v1/controls/workspace")
                        .param("project", "ground-control")
                        .param("freshnessWindowDays", "0"))
                .andExpect(status().isBadRequest());
    }

    private static ControlWorkspaceResult sampleWorkspace() {
        var control = new ControlWorkspaceResult.WorkspaceControl(
                CONTROL_ID,
                "CTL-001",
                "Payment approval",
                "Approves payments",
                "Prevent unapproved payments",
                ControlFunction.PREVENTIVE,
                ControlStatus.OPERATIONAL,
                "alice",
                "Payments production",
                "finance",
                "internal",
                List.of(new ControlWorkspaceResult.WorkspaceScopedImplementation(
                        UUID.fromString("00000000-0000-0000-0000-000000000201"),
                        "SCI-001",
                        "Payments deployment",
                        "Payments production only",
                        null,
                        null,
                        null)),
                List.of(new ControlWorkspaceResult.WorkspaceControlTest(
                        UUID.fromString("00000000-0000-0000-0000-000000000301"),
                        "CTEST-001",
                        "INSPECTION",
                        ControlTestConclusion.EFFECTIVE,
                        "auditor",
                        LocalDate.parse("2026-05-31"),
                        "All approvals present")),
                List.of(new ControlWorkspaceResult.WorkspaceAssessment(
                        UUID.fromString("00000000-0000-0000-0000-000000000401"),
                        "CEA-001",
                        "EFFECTIVE",
                        "EFFECTIVE",
                        LocalDate.parse("2026-05-31"),
                        "assessor",
                        List.of("00000000-0000-0000-0000-000000000301"))),
                List.of(new ControlWorkspaceResult.WorkspaceEvidence(
                        UUID.fromString("00000000-0000-0000-0000-000000000501"),
                        "EV-001",
                        "Approval evidence",
                        "Control evidence summary",
                        "CONTROL_TEST_SUMMARY",
                        AS_OF)),
                List.of(new ControlWorkspaceResult.WorkspaceFinding(
                        UUID.fromString("00000000-0000-0000-0000-000000000601"),
                        "FIND-001",
                        "Approval exception",
                        "CONTROL_DEFICIENCY",
                        "HIGH",
                        "OPEN",
                        "owner",
                        null)),
                List.of(new ControlWorkspaceResult.WorkspaceRiskMapping(
                        UUID.fromString("00000000-0000-0000-0000-000000000701"),
                        "PREVENTIVE",
                        "RS-001",
                        "Approval bypass",
                        "Prevent unapproved payments",
                        List.of(new ControlWorkspaceResult.WorkspaceMappingEvidenceRef(
                                "EVD-REF-001",
                                "Approval packet",
                                UUID.fromString("00000000-0000-0000-0000-000000000501"))))),
                List.of("CURRENT"));
        return new ControlWorkspaceResult(List.of(control));
    }
}
