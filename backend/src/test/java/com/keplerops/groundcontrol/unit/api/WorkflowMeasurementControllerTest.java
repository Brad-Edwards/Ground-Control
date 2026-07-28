package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.workflowtelemetry.WorkflowRunController;
import com.keplerops.groundcontrol.api.workflowtelemetry.stream.WorkflowRunStreamHub;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingDisposition;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingSourceKind;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowGateFinding;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.StationYieldCalculator;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
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
class WorkflowMeasurementControllerTest {

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

    // ---- measurement projection (issue #1355) --------------------------------

    @Test
    void measurementReportsCoverageAlongsideEveryRatio() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("gc");
        when(measurementService.resolveReportingWindow(any(), any()))
                .thenReturn(new WorkflowMeasurementService.Window(
                        Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-28T00:00:00Z")));
        when(measurementService.aggregateStationYield(eq("gc"), any(), any()))
                .thenReturn(java.util.Map.of(
                        "ci",
                        new StationYieldCalculator.StationYield("ci", 3, 4, 6, 2, 1, java.util.Map.of(1, 3L, 3, 1L))));
        when(measurementService.aggregateFindingCounts(eq("gc"), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/workflow-runs/measurement").param("project", "gc"))
                .andExpect(status().isOk())
                // A percentage without its coverage is not a process fact, so numerator,
                // denominator and unresolved count travel with the ratio.
                .andExpect(jsonPath("$.measurementVersion", is("gc.measurement/v1")))
                .andExpect(jsonPath("$.stations[0].stationId", is("ci")))
                .andExpect(jsonPath("$.stations[0].firstPassNumerator", is(3)))
                .andExpect(jsonPath("$.stations[0].firstPassDenominator", is(4)))
                .andExpect(jsonPath("$.stations[0].firstPassYield", is(0.75)))
                .andExpect(jsonPath("$.stations[0].unresolvedRuns", is(1)))
                .andExpect(jsonPath("$.stations[0].reworkAttempts", is(2)));
    }

    @Test
    void measurementReportsNullYieldRatherThanZeroWhenNothingWasMeasured() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("gc");
        when(measurementService.resolveReportingWindow(any(), any()))
                .thenReturn(new WorkflowMeasurementService.Window(
                        Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-28T00:00:00Z")));
        when(measurementService.aggregateStationYield(eq("gc"), any(), any()))
                .thenReturn(java.util.Map.of(
                        "vale", new StationYieldCalculator.StationYield("vale", 0, 0, 0, 0, 0, java.util.Map.of())));
        when(measurementService.aggregateFindingCounts(eq("gc"), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/workflow-runs/measurement").param("project", "gc"))
                .andExpect(status().isOk())
                // "measured zero" and "nothing was measured" are different claims; rendering the
                // second as 0% would report a station that always fails.
                .andExpect(jsonPath("$.stations[0].firstPassYield").doesNotExist());
    }

    @Test
    void measurementCountsFindingsByReviewerCategoryAndSeverity() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("gc");
        when(measurementService.resolveReportingWindow(any(), any()))
                .thenReturn(new WorkflowMeasurementService.Window(
                        Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-28T00:00:00Z")));
        when(measurementService.aggregateStationYield(eq("gc"), any(), any())).thenReturn(java.util.Map.of());
        when(measurementService.aggregateFindingCounts(eq("gc"), any(), any()))
                .thenReturn(List.of(
                        new Object[] {
                            "sonarcloud",
                            FindingSourceKind.DETECTOR,
                            "sonarcloud",
                            "java:S1192",
                            "MAJOR",
                            FindingDisposition.OPEN,
                            7L
                        },
                        // A reviewer finding carries no severity; the bucket is reported rather than
                        // dropped, or the counts would disagree with the findings behind them.
                        new Object[] {
                            "codex_review", FindingSourceKind.REVIEWER, "core", null, null, FindingDisposition.OPEN, 2L
                        }));

        mockMvc.perform(get("/api/v1/workflow-runs/measurement").param("project", "gc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findingCounts", hasSize(2)))
                .andExpect(jsonPath("$.findingCounts[0].sourceId", is("sonarcloud")))
                .andExpect(jsonPath("$.findingCounts[0].category", is("java:S1192")))
                .andExpect(jsonPath("$.findingCounts[0].severity", is("MAJOR")))
                .andExpect(jsonPath("$.findingCounts[0].count", is(7)))
                .andExpect(jsonPath("$.findingCounts[1].sourceKind", is("REVIEWER")))
                .andExpect(jsonPath("$.findingCounts[1].severity").doesNotExist());
    }

    @Test
    void recordFindingDispositionReturnsTheUpdatedFinding() throws Exception {
        var finding = new WorkflowGateFinding(
                UUID.randomUUID(), UUID.randomUUID(), "gc", "policy", FindingSourceKind.DETECTOR, "policy", "k1");
        finding.applyDisposition(FindingDisposition.FIXED);
        setField(finding, "id", UUID.randomUUID());
        when(projectService.requireProjectIdentifier(any())).thenReturn("gc");
        when(measurementService.recordFindingDisposition(any(), eq("gc"), eq(FindingDisposition.FIXED)))
                .thenReturn(finding);

        mockMvc.perform(post("/api/v1/workflow-runs/findings/" + UUID.randomUUID() + "/disposition")
                        .param("project", "gc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"FIXED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition", is("FIXED")))
                .andExpect(jsonPath("$.sourceId", is("policy")))
                // The projection must not echo review prose back out.
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void recordFindingDispositionRejectsAMissingDisposition() throws Exception {
        when(projectService.requireProjectIdentifier(any())).thenReturn("gc");

        mockMvc.perform(post("/api/v1/workflow-runs/findings/" + UUID.randomUUID() + "/disposition")
                        .param("project", "gc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                // 422 is this repo's shared envelope for a validation failure, not 400.
                .andExpect(status().isUnprocessableEntity());

        verify(measurementService, never()).recordFindingDisposition(any(), any(), any());
    }
}
