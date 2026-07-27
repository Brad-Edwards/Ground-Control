package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.workflowtelemetry.WorkflowRunController;
import com.keplerops.groundcontrol.api.workflowtelemetry.stream.WorkflowRunStreamHub;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService.RunAggregate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(WorkflowRunController.class)
class WorkflowRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowTelemetryService telemetryService;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private WorkflowRunStreamHub streamHub;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000859");

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

    private static WorkflowRun sampleRun() {
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        setField(run, "id", RUN_ID);
        run.setIssueNumber(859);
        run.setBranch("859-feature");
        run.setFinalState(WorkflowRunState.READY_FOR_REVIEW);
        return run;
    }

    private static WorkflowPhaseEvent sampleEvent(UUID runId) {
        var event = new WorkflowPhaseEvent(
                runId, "ground-control", "ci", PhaseEventType.COMPLETED, FROM, 1000L, TelemetryProvenance.ISSUE_THREAD);
        event.setCycleIndex(1);
        event.setOutcome("clean");
        event.setSourceId("ci:COMPLETED:1");
        return event;
    }

    private static WorkflowPhaseEvent startedEvent(UUID runId) {
        var event = new WorkflowPhaseEvent(
                runId, "ground-control", "ci", PhaseEventType.STARTED, FROM, null, TelemetryProvenance.LIVE_EMISSION);
        event.setCycleIndex(0);
        event.setSourceId("ci:STARTED:0");
        return event;
    }

    private static RunAggregate sampleAggregate() {
        return new RunAggregate(
                FROM,
                TO,
                7,
                3,
                1,
                2,
                0,
                0,
                0,
                12.0,
                30.0,
                45.0,
                new BigDecimal("100.0000"),
                new BigDecimal("60.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("20.0000"),
                new BigDecimal("10.0000"),
                50,
                600,
                1_000_000,
                List.of(new WorkflowTelemetryService.PhaseHotspot("ci", 5, 2, 0, 1000L, 2000L, 3)));
    }
}
