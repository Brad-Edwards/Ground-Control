package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.workflowtelemetry.WorkflowRunController;
import com.keplerops.groundcontrol.api.workflowtelemetry.stream.WorkflowRunStreamHub;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(WorkflowRunController.class)
class WorkflowRunStreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowTelemetryService telemetryService;

    @MockitoBean
    private WorkflowMeasurementService measurementService;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private WorkflowRunStreamHub streamHub;

    // ---- POST /api/v1/workflow-runs ------------------------------------------------------------

    // ---- GET /api/v1/workflow-runs/stream (issue #1436) ----------------------------------------

    @Test
    void streamRegistersTheResolvedProjectAndAnswersAsAnEventStream() throws Exception {
        when(projectService.requireProjectIdentifier("ground-control")).thenReturn("ground-control");
        var emitter = new SseEmitter(1000L);
        when(streamHub.subscribe(eq("ground-control"), any())).thenReturn(emitter);

        // A stream response is an async dispatch: the content type is only observable once the
        // emitter finishes, so complete it and dispatch rather than asserting on the initial return.
        var started = mockMvc.perform(get("/api/v1/workflow-runs/stream").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        emitter.complete();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        // The project the hub scopes fan-out to is the one ProjectService resolved, never the raw
        // query parameter.
        verify(streamHub).subscribe(eq("ground-control"), any());
    }

    @Test
    void streamRefusesAnUnknownProjectBeforeRegisteringAnything() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenThrow(new NotFoundException("Project not found"));

        mockMvc.perform(get("/api/v1/workflow-runs/stream").param("project", "nope"))
                .andExpect(status().isNotFound());

        verify(streamHub, never()).subscribe(any(), any());
    }

    @Test
    void streamRendersCapacityRejectionThroughTheStandardErrorEnvelope() throws Exception {
        // Capacity is refused before the event-stream headers commit, which is the only window in
        // which an HTTP error envelope is still possible.
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(streamHub.subscribe(any(), any()))
                .thenThrow(new ServiceUnavailableException("Workflow-run stream connection capacity reached"));

        mockMvc.perform(get("/api/v1/workflow-runs/stream").param("project", "ground-control"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code", is("service_unavailable")));
    }
}
