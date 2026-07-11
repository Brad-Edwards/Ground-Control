package com.keplerops.groundcontrol.unit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.workflowexecution.WorkflowExecutionController;
import com.keplerops.groundcontrol.domain.exception.AuthorizationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalType;
import com.keplerops.groundcontrol.domain.workflowexecution.RetryPhase;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionRef;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionStatus;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionView;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowOutcome;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService.SignalRequest;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService.StartRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(WorkflowExecutionController.class)
class WorkflowExecutionControllerTest {

    private static final String WORKFLOW_ID = "gc-implement-ground-control-1278";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowExecutionService service;

    @Test
    void startReturns201() throws Exception {
        when(service.start(any(), any()))
                .thenReturn(new WorkflowExecutionRef(WORKFLOW_ID, "run-1", WorkflowType.IMPLEMENT, "ground-control"));

        mockMvc.perform(
                        post("/api/v1/workflow-executions")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "workflowType": "IMPLEMENT",
                                  "issueNumber": 1278,
                                  "sonarProjectKey": "sonar-key",
                                  "reviewCap": 3,
                                  "requirementUids": ["GC-O009"],
                                  "pollIntervalSeconds": 120
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowId", is(WORKFLOW_ID)))
                .andExpect(jsonPath("$.runId", is("run-1")))
                .andExpect(jsonPath("$.workflowType", is("IMPLEMENT")));

        // Verify the controller's request-DTO -> domain StartRequest field mapping, not just the status.
        var captor = ArgumentCaptor.forClass(StartRequest.class);
        verify(service).start(eq("ground-control"), captor.capture());
        var mapped = captor.getValue();
        assertThat(mapped.workflowType()).isEqualTo(WorkflowType.IMPLEMENT);
        assertThat(mapped.issueNumber()).isEqualTo(1278);
        assertThat(mapped.sonarProjectKey()).isEqualTo("sonar-key");
        assertThat(mapped.reviewCap()).isEqualTo(3);
        assertThat(mapped.requirementUids()).containsExactly("GC-O009");
        assertThat(mapped.pollIntervalSeconds()).isEqualTo(120);
    }

    @Test
    void startWithMissingWorkflowTypeReturns422() throws Exception {
        mockMvc.perform(post("/api/v1/workflow-executions")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"issueNumber\": 1278 }"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void startWithUnknownWorkflowTypeReturns422() throws Exception {
        mockMvc.perform(post("/api/v1/workflow-executions")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"workflowType\": \"NOT_A_TYPE\", \"issueNumber\": 1278 }"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listReturns200() throws Exception {
        when(service.list(any(), any())).thenReturn(List.of(sampleView()));

        mockMvc.perform(get("/api/v1/workflow-executions").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].workflowId", is(WORKFLOW_ID)))
                .andExpect(jsonPath("$[0].status", is("RUNNING")));
    }

    @Test
    void getReturns200() throws Exception {
        when(service.get(any(), eq(WORKFLOW_ID))).thenReturn(sampleView());

        mockMvc.perform(get("/api/v1/workflow-executions/" + WORKFLOW_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId", is(WORKFLOW_ID)))
                .andExpect(jsonPath("$.issueNumber", is(1278)))
                // Bounded gate-state read model for the operations console (GC-Q016).
                .andExpect(jsonPath("$.gateState.phase", is("D_SHIP_PIPELINE")))
                .andExpect(jsonPath("$.gateState.outcome", is("READY_FOR_REVIEW")))
                .andExpect(jsonPath("$.gateState.waitingForMerge", is(true)));
    }

    @Test
    void getUnknownExecutionReturns404() throws Exception {
        when(service.get(any(), any()))
                .thenThrow(new NotFoundException("Workflow execution not found: " + WORKFLOW_ID));

        mockMvc.perform(get("/api/v1/workflow-executions/" + WORKFLOW_ID).param("project", "ground-control"))
                .andExpect(status().isNotFound());
    }

    @Test
    void signalReturns202AndMapsAllSignalFields() throws Exception {
        mockMvc.perform(
                        post("/api/v1/workflow-executions/" + WORKFLOW_ID + "/signals")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "signalType": "REVIEW_CAP_DISPOSITION",
                                  "reason": "operator override",
                                  "reviewer": "TEST_QUALITY",
                                  "disposition": "ONE_MORE_CYCLE"
                                }
                                """))
                .andExpect(status().isAccepted());

        // Verify the controller's request-DTO -> domain SignalRequest field mapping across every field.
        var captor = ArgumentCaptor.forClass(SignalRequest.class);
        verify(service).signal(eq("ground-control"), eq(WORKFLOW_ID), captor.capture());
        var mapped = captor.getValue();
        assertThat(mapped.type()).isEqualTo(OperatorSignalType.REVIEW_CAP_DISPOSITION);
        assertThat(mapped.reason()).isEqualTo("operator override");
        assertThat(mapped.reviewer()).isEqualTo(Reviewer.TEST_QUALITY);
        assertThat(mapped.disposition()).isEqualTo(SignalDisposition.ONE_MORE_CYCLE);
    }

    @Test
    void signalReturns403WhenGateAuthorityDenied() throws Exception {
        doThrow(new AuthorizationException("Operator gate signals require an authenticated actor with gate authority"))
                .when(service)
                .signal(eq("ground-control"), eq(WORKFLOW_ID), any());

        mockMvc.perform(post("/api/v1/workflow-executions/" + WORKFLOW_ID + "/signals")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"signalType\": \"CANCEL\", \"reason\": \"stop\" }"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", is("authorization_error")));
    }

    @Test
    void signalWithMissingSignalTypeReturns422() throws Exception {
        mockMvc.perform(post("/api/v1/workflow-executions/" + WORKFLOW_ID + "/signals")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"operator stop\" }"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void signalWithInvalidDispositionReturns422() throws Exception {
        mockMvc.perform(
                        post("/api/v1/workflow-executions/" + WORKFLOW_ID + "/signals")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                { "signalType": "REVIEW_CAP_DISPOSITION", "reviewer": "CODEX", "disposition": "NOPE" }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    private static WorkflowExecutionView sampleView() {
        return new WorkflowExecutionView(
                WORKFLOW_ID,
                "run-1",
                WorkflowType.IMPLEMENT,
                WorkflowExecutionStatus.RUNNING,
                null,
                null,
                3L,
                new WorkflowExecutionView.Correlation("ground-control", 1278, List.of("GC-O009")),
                new WorkflowExecutionView.GateState(
                        RetryPhase.D_SHIP_PIPELINE, WorkflowOutcome.READY_FOR_REVIEW, true, null, null));
    }
}
