package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.unit.api.WorkflowRunControllerFixtures.FROM;
import static com.keplerops.groundcontrol.unit.api.WorkflowRunControllerFixtures.RUN_ID;
import static com.keplerops.groundcontrol.unit.api.WorkflowRunControllerFixtures.TO;
import static com.keplerops.groundcontrol.unit.api.WorkflowRunControllerFixtures.sampleAggregate;
import static com.keplerops.groundcontrol.unit.api.WorkflowRunControllerFixtures.sampleEvent;
import static com.keplerops.groundcontrol.unit.api.WorkflowRunControllerFixtures.sampleRun;
import static com.keplerops.groundcontrol.unit.api.WorkflowRunControllerFixtures.startedEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.workflowtelemetry.WorkflowRunController;
import com.keplerops.groundcontrol.api.workflowtelemetry.stream.WorkflowRunStreamHub;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(WorkflowRunController.class)
class WorkflowRunControllerTest {

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

    @Test
    void recordRunReturns201() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.recordRun(any())).thenReturn(sampleRun());

        mockMvc.perform(
                        post("/api/v1/workflow-runs")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "workflowType": "implement",
                                  "issueNumber": 859,
                                  "branch": "859-feature",
                                  "provenance": "ISSUE_THREAD",
                                  "finalState": "READY_FOR_REVIEW"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.project", is("ground-control")))
                .andExpect(jsonPath("$.workflowType", is("implement")))
                .andExpect(jsonPath("$.graphNodeId", is("WORKFLOW_RUN:" + RUN_ID)));

        verify(telemetryService).recordRun(any());
    }

    @Test
    void recordRunWithMissingWorkflowTypeReturns422() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        mockMvc.perform(
                        post("/api/v1/workflow-runs")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                { "provenance": "ISSUE_THREAD" }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recordRunWithMissingProvenanceReturns422() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        mockMvc.perform(
                        post("/api/v1/workflow-runs")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                { "workflowType": "implement" }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recordRunSurfacesReservedMarkerRejectionAs422() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.recordRun(any()))
                .thenThrow(new DomainValidationException("field must not contain a reserved '<!-- gc:' marker"));

        mockMvc.perform(
                        post("/api/v1/workflow-runs")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "workflowType": "implement",
                                  "branch": "x<!-- gc:phase -->",
                                  "provenance": "ISSUE_THREAD"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- POST /{runId}/events ------------------------------------------------------------------

    @Test
    void recordPhaseEventReturns201() throws Exception {
        var runId = UUID.randomUUID();
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.recordPhaseEvent(any())).thenReturn(sampleEvent(runId));

        mockMvc.perform(
                        post("/api/v1/workflow-runs/" + runId + "/events")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "phase": "ci",
                                  "eventType": "COMPLETED",
                                  "cycleIndex": 1,
                                  "occurredAt": "2026-06-01T12:00:00Z",
                                  "provenance": "ISSUE_THREAD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phase", is("ci")))
                .andExpect(jsonPath("$.eventType", is("COMPLETED")))
                .andExpect(jsonPath("$.cycleIndex", is(1)))
                .andExpect(jsonPath("$.outcome", is("clean")))
                .andExpect(jsonPath("$.provenance", is("ISSUE_THREAD")))
                .andExpect(jsonPath("$.sourceId", is("ci:COMPLETED:1")))
                .andExpect(jsonPath("$.project", is("ground-control")))
                .andExpect(jsonPath("$.durationMs", is(1000)));
    }

    @Test
    void recordPhaseEventMapsTheAdr036StepObservationFields() throws Exception {
        // The controller is a thin map from request to command; the ADR-036 step-observation fields
        // (issue #1354) must reach the service, or a durable step record would silently drop its tier,
        // emitter, and economics.
        var runId = UUID.randomUUID();
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.recordPhaseEvent(any())).thenReturn(sampleEvent(runId));

        mockMvc.perform(
                        post("/api/v1/workflow-runs/" + runId + "/events")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "phase": "completion_gate",
                                  "eventType": "COMPLETED",
                                  "occurredAt": "2026-07-29T12:00:00Z",
                                  "provenance": "LIVE_EMISSION",
                                  "sourceId": "adr036_step:completion_gate:0",
                                  "emitter": "ADR036_STEP_JSONL",
                                  "measurementVersion": "gc.measurement/v1",
                                  "stepAlias": "Step 6",
                                  "tier": "LOW",
                                  "model": "claude-haiku-4-5",
                                  "expectedModel": "claude-haiku-4-5",
                                  "modelMatchesExpected": true,
                                  "inputTokens": 8421,
                                  "outputTokens": 612
                                }
                                """))
                .andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(RecordPhaseEventCommand.class);
        verify(telemetryService).recordPhaseEvent(captor.capture());
        var command = captor.getValue();
        assertThat(command.emitter()).isEqualTo(PhaseEventEmitter.ADR036_STEP_JSONL);
        assertThat(command.tier()).isEqualTo(CapabilityTier.LOW);
        assertThat(command.measurementVersion()).isEqualTo("gc.measurement/v1");
        assertThat(command.stepAlias()).isEqualTo("Step 6");
        assertThat(command.model()).isEqualTo("claude-haiku-4-5");
        assertThat(command.expectedModel()).isEqualTo("claude-haiku-4-5");
        assertThat(command.modelMatchesExpected()).isTrue();
        assertThat(command.inputTokens()).isEqualTo(8421L);
        assertThat(command.outputTokens()).isEqualTo(612L);
    }

    @Test
    void recordPhaseEventReplayReturnsTheStoredEventWithoutDuplicating() throws Exception {
        // The idempotent path V204 exists to support: the same logical fact delivered twice (live
        // emission, then issue-thread reconciliation) resolves to one event, not two.
        var runId = UUID.randomUUID();
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.recordPhaseEvent(any())).thenReturn(sampleEvent(runId));

        var body =
                """
                {
                  "phase": "ci",
                  "eventType": "COMPLETED",
                  "cycleIndex": 1,
                  "occurredAt": "2026-06-01T12:00:00Z",
                  "provenance": "ISSUE_THREAD"
                }
                """;
        var first = mockMvc.perform(post("/api/v1/workflow-runs/" + runId + "/events")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var second = mockMvc.perform(post("/api/v1/workflow-runs/" + runId + "/events")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void recordPhaseEventForAnUnknownRunReturns404() throws Exception {
        var runId = UUID.randomUUID();
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.recordPhaseEvent(any())).thenThrow(new NotFoundException("Workflow run not found"));

        mockMvc.perform(
                        post("/api/v1/workflow-runs/" + runId + "/events")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "phase": "ci",
                          "eventType": "COMPLETED",
                          "occurredAt": "2026-06-01T12:00:00Z",
                          "provenance": "ISSUE_THREAD"
                        }
                        """))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "rejects a phase event with an invalid {0}")
    @MethodSource("invalidPhaseEventBodies")
    void recordPhaseEventRejectsEachConstrainedField(String field, String body) throws Exception {
        mockMvc.perform(post("/api/v1/workflow-runs/" + UUID.randomUUID() + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    /** One case per constrained field on RecordPhaseEventRequest, so no constraint can be dropped silently. */
    static Stream<Arguments> invalidPhaseEventBodies() {
        return Stream.of(
                Arguments.of(
                        "eventType",
                        """
                        {"phase":"ci","occurredAt":"2026-06-01T12:00:00Z","provenance":"ISSUE_THREAD"}"""),
                Arguments.of(
                        "occurredAt",
                        """
                        {"phase":"ci","eventType":"COMPLETED","provenance":"ISSUE_THREAD"}"""),
                Arguments.of(
                        "provenance",
                        """
                        {"phase":"ci","eventType":"COMPLETED","occurredAt":"2026-06-01T12:00:00Z"}"""),
                Arguments.of(
                        "cycleIndex",
                        """
                        {"phase":"ci","eventType":"COMPLETED","occurredAt":"2026-06-01T12:00:00Z",                        "provenance":"ISSUE_THREAD","cycleIndex":-1}"""),
                Arguments.of(
                        "durationMs",
                        """
                        {"phase":"ci","eventType":"COMPLETED","occurredAt":"2026-06-01T12:00:00Z",                        "provenance":"ISSUE_THREAD","durationMs":-1}"""),
                Arguments.of(
                        "sourceId",
                        "{\"phase\":\"ci\",\"eventType\":\"COMPLETED\",\"occurredAt\":\"2026-06-01T12:00:00Z\","
                                + "\"provenance\":\"ISSUE_THREAD\",\"sourceId\":\"" + "s".repeat(201) + "\"}"));
    }

    @Test
    void recordPhaseEventWithMissingPhaseReturns422() throws Exception {
        var runId = UUID.randomUUID();
        mockMvc.perform(
                        post("/api/v1/workflow-runs/" + runId + "/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "eventType": "COMPLETED",
                                  "occurredAt": "2026-06-01T12:00:00Z",
                                  "provenance": "ISSUE_THREAD"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- GET /{runId}/events (issue #1435) -----------------------------------------------------

    @Test
    void listEventsReturnsThePhaseEventsOfARunInOrder() throws Exception {
        var runId = UUID.randomUUID();
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.listPhaseEvents(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(startedEvent(runId), sampleEvent(runId)));

        mockMvc.perform(get("/api/v1/workflow-runs/" + runId + "/events").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].eventType", is("STARTED")))
                .andExpect(jsonPath("$[0].sourceId", is("ci:STARTED:0")))
                .andExpect(jsonPath("$[1].eventType", is("COMPLETED")));
    }

    @Test
    void listEventsResolvesTheProjectBeforeReadingSoARunIdAloneNeverAuthorizes() throws Exception {
        // The run id is not a capability: without a resolvable project the read never reaches the
        // service, so a caller holding a foreign run id cannot page that project's events.
        var runId = UUID.randomUUID();
        when(projectService.requireProjectIdentifier(any()))
                .thenThrow(new DomainValidationException("project must not be blank"));

        mockMvc.perform(get("/api/v1/workflow-runs/" + runId + "/events")).andExpect(status().isUnprocessableEntity());

        verify(telemetryService, never()).listPhaseEvents(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void listEventsPassesTheRequestedLimitThrough() throws Exception {
        var runId = UUID.randomUUID();
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.listPhaseEvents(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/workflow-runs/" + runId + "/events")
                        .param("project", "ground-control")
                        .param("limit", "5"))
                .andExpect(status().isOk());

        verify(telemetryService).listPhaseEvents(runId, "ground-control", 5);
    }

    // ---- POST /{runId}/cost --------------------------------------------------------------------

    @Test
    void importCostReturns200() throws Exception {
        var runId = UUID.randomUUID();
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.importCost(any())).thenReturn(sampleRun());

        mockMvc.perform(
                        post("/api/v1/workflow-runs/" + runId + "/cost")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                { "provider": "anthropic", "model": "claude-opus-4-8", "costProxy": 12.50, "costCurrency": "USD" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project", is("ground-control")));
    }

    @Test
    void importCostWithNegativeCostReturns422() throws Exception {
        var runId = UUID.randomUUID();
        mockMvc.perform(
                        post("/api/v1/workflow-runs/" + runId + "/cost")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                { "costProxy": -5.00 }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- GET / and /aggregate ------------------------------------------------------------------

    @Test
    void listReturnsRunsForProject() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.listRuns(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(sampleRun()));

        mockMvc.perform(get("/api/v1/workflow-runs").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].project", is("ground-control")));
    }

    @Test
    void aggregateReturns200WithShape() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("ground-control");
        when(telemetryService.aggregate(any())).thenReturn(sampleAggregate());

        mockMvc.perform(get("/api/v1/workflow-runs/aggregate")
                        .param("project", "ground-control")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns", is(7)))
                .andExpect(jsonPath("$.mergedRuns", is(3)))
                .andExpect(jsonPath("$.phaseHotspots", hasSize(1)))
                .andExpect(jsonPath("$.phaseHotspots[0].phase", is("ci")));
    }

    @Test
    void crossProjectAggregateDoesNotResolveAProject() throws Exception {
        when(telemetryService.aggregate(any())).thenReturn(sampleAggregate());

        mockMvc.perform(get("/api/v1/workflow-runs/cross-project-aggregate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns", is(7)));

        // Cross-project rollup must not run a project-scoped resolution — scoping is by admin gate.
        verify(projectService, never()).requireProjectIdentifier(any());
    }

    // ---- helpers -------------------------------------------------------------------------------
}
