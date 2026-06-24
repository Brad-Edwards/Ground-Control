package com.keplerops.groundcontrol.unit.api;

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
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
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
import org.junit.jupiter.api.Test;
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
    private ProjectService projectService;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");

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
                .andExpect(jsonPath("$.workflowType", is("implement")));

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
                .andExpect(jsonPath("$.phase", is("ci")));
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

    private static WorkflowRun sampleRun() {
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        run.setIssueNumber(859);
        run.setBranch("859-feature");
        run.setFinalState(WorkflowRunState.READY_FOR_REVIEW);
        return run;
    }

    private static WorkflowPhaseEvent sampleEvent(UUID runId) {
        return new WorkflowPhaseEvent(
                runId,
                "ground-control",
                "ci",
                PhaseEventType.COMPLETED,
                1,
                FROM,
                1000L,
                "clean",
                TelemetryProvenance.ISSUE_THREAD);
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
