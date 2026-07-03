package com.keplerops.groundcontrol.unit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.api.research.ResearchOperationAuthorizationController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataForm;
import com.keplerops.groundcontrol.domain.research.model.ResearchDestinationClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchHighRiskOperationKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunOperationAuthorization;
import com.keplerops.groundcontrol.domain.research.service.DecideOperationAuthorizationCommand;
import com.keplerops.groundcontrol.domain.research.service.RequestOperationAuthorizationCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchOperationAuthorizationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice for {@link ResearchOperationAuthorizationController}
 * (GC-RSCH-R005 / ADR-086). Verifies status codes, DTO validation, and that each
 * request DTO's {@code toCommand()} is forwarded with request-derived fields.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ResearchOperationAuthorizationController.class)
class ResearchOperationAuthorizationControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID AUTH_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchOperationAuthorizationService authorizationService;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void requestReturns201AndForwardsCommand() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(authorizationService.requestAuthorization(eq(PROJECT_ID), eq(RUN_ID), any()))
                .thenReturn(authorization());

        mockMvc.perform(post("/api/v1/research-runs/{runId}/operation-authorizations", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationKind\":\"EXTERNAL_WRITE\",\"dataClass\":\"CONFIDENTIAL\","
                                + "\"destinationClass\":\"AI_PROVIDER\",\"requestedForm\":\"SUMMARY\","
                                + "\"toolId\":\"scholarly\",\"sandboxProfile\":\"default\","
                                + "\"summary\":\"external write\",\"sourceActionId\":\"src-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operationKind").value("EXTERNAL_WRITE"))
                .andExpect(jsonPath("$.state").value("PROPOSED"));

        ArgumentCaptor<RequestOperationAuthorizationCommand> captor =
                ArgumentCaptor.forClass(RequestOperationAuthorizationCommand.class);
        verify(authorizationService).requestAuthorization(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        assertThat(captor.getValue().operationKind()).isEqualTo(ResearchHighRiskOperationKind.EXTERNAL_WRITE);
        assertThat(captor.getValue().dataClass()).isEqualTo(ResearchDataClass.CONFIDENTIAL);
        assertThat(captor.getValue().requestedForm()).isEqualTo(ResearchDataForm.SUMMARY);
    }

    @Test
    void requestRejectsMissingOperationKindWith422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{runId}/operation-authorizations", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataClass\":\"CONFIDENTIAL\",\"destinationClass\":\"AI_PROVIDER\","
                                + "\"requestedForm\":\"SUMMARY\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listReturns200() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(authorizationService.listAuthorizations(PROJECT_ID, RUN_ID))
                .thenReturn(java.util.List.of(authorization()));

        mockMvc.perform(get("/api/v1/research-runs/{runId}/operation-authorizations", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("PROPOSED"));
    }

    @Test
    void decisionForwardsApproveFlag() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(authorizationService.decideAuthorization(eq(PROJECT_ID), eq(RUN_ID), eq(AUTH_ID), any()))
                .thenReturn(authorization());

        mockMvc.perform(post("/api/v1/research-runs/{runId}/operation-authorizations/{id}/decision", RUN_ID, AUTH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"note\":\"ok\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<DecideOperationAuthorizationCommand> captor =
                ArgumentCaptor.forClass(DecideOperationAuthorizationCommand.class);
        verify(authorizationService).decideAuthorization(eq(PROJECT_ID), eq(RUN_ID), eq(AUTH_ID), captor.capture());
        assertThat(captor.getValue().approve()).isTrue();
    }

    @Test
    void consumeReturns200() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(authorizationService.consumeAuthorization(PROJECT_ID, RUN_ID, AUTH_ID))
                .thenReturn(authorization());

        mockMvc.perform(post("/api/v1/research-runs/{runId}/operation-authorizations/{id}/consume", RUN_ID, AUTH_ID))
                .andExpect(status().isOk());
    }

    private ResearchRunOperationAuthorization authorization() {
        var project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        TestUtil.setField(project, "id", PROJECT_ID);
        var run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        TestUtil.setField(run, "id", RUN_ID);
        var authorization = new ResearchRunOperationAuthorization(
                run,
                ResearchHighRiskOperationKind.EXTERNAL_WRITE,
                ResearchDataClass.CONFIDENTIAL,
                ResearchDestinationClass.AI_PROVIDER,
                ResearchDataForm.SUMMARY);
        TestUtil.setField(authorization, "id", AUTH_ID);
        return authorization;
    }
}
