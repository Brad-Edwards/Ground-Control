package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.unit.api.WorkflowRunControllerFixtures.sampleRun;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.workflowtelemetry.WorkflowRunController;
import com.keplerops.groundcontrol.api.workflowtelemetry.stream.WorkflowRunStreamHub;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowActivityService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowActivitySnapshot;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(WorkflowRunController.class)
class WorkflowActivityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowTelemetryService telemetryService;

    @MockitoBean
    private WorkflowMeasurementService measurementService;

    @MockitoBean
    private WorkflowActivityService activityService;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private WorkflowRunStreamHub streamHub;

    @Test
    void activityReturnsTheBoundedProjectSnapshot() throws Exception {
        var asOf = Instant.parse("2026-07-30T12:00:00Z");
        var openRun = new WorkflowActivitySnapshot.OpenRun(
                sampleRun(),
                "completion_gate",
                "Completion gate",
                asOf.minusSeconds(60),
                2,
                Duration.ofMinutes(30),
                new WorkflowActivitySnapshot.RoutingObservation(
                        "implementation",
                        "05",
                        CapabilityTier.HIGH,
                        "claude-opus",
                        "claude-opus",
                        true,
                        asOf.minusSeconds(90)),
                List.of(new WorkflowActivitySnapshot.GateAttempt(
                        "codex_review",
                        "Codex review",
                        PhaseEventType.FAILED,
                        StationResult.FAIL,
                        2,
                        asOf.minusSeconds(120),
                        12_000L,
                        4,
                        2)));
        when(projectService.requireProjectIdentifier("ground-control")).thenReturn("ground-control");
        when(activityService.snapshot("ground-control"))
                .thenReturn(new WorkflowActivitySnapshot(asOf, 4, true, List.of(openRun), List.of(sampleRun())));

        mockMvc.perform(get("/api/v1/workflow-runs/activity").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf", is("2026-07-30T12:00:00Z")))
                .andExpect(jsonPath("$.openRunTotal", is(4)))
                .andExpect(jsonPath("$.openRunsTruncated", is(true)))
                .andExpect(jsonPath("$.openRuns", hasSize(1)))
                .andExpect(jsonPath("$.openRuns[0].currentPhaseTitle", is("Completion gate")))
                .andExpect(jsonPath("$.openRuns[0].currentCycle", is(2)))
                .andExpect(jsonPath("$.openRuns[0].routing.model", is("claude-opus")))
                .andExpect(jsonPath("$.openRuns[0].gates[0].stationResult", is("FAIL")))
                .andExpect(jsonPath("$.openRuns[0].gates[0].findingCount", is(4)))
                .andExpect(jsonPath("$.openRuns[0].gates[0].findingsDropped", is(2)))
                .andExpect(jsonPath("$.recentlyFinished", hasSize(1)));

        verify(activityService).snapshot("ground-control");
    }
}
