package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.mcptelemetry.McpTelemetryController;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.mcptelemetry.service.McpTelemetryService;
import java.time.Instant;
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
@WebMvcTest(McpTelemetryController.class)
class McpTelemetryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private McpTelemetryService telemetryService;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");

    // ------------------------------------------------------------------
    // POST /api/v1/mcp-tool-usage/events
    // ------------------------------------------------------------------

    @Test
    void postEventReturns201() throws Exception {
        mockMvc.perform(
                        post("/api/v1/mcp-tool-usage/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "tool": "gc_query",
                                            "action": "list",
                                            "outcome": "ok",
                                            "durationMs": 42,
                                            "project": "ground-control",
                                            "ts": "2026-06-01T12:00:00Z"
                                        }
                                        """))
                .andExpect(status().isCreated());

        verify(telemetryService).recordEvent(any());
    }

    @Test
    void postEventMapsAllFieldsToCommand() throws Exception {
        mockMvc.perform(
                        post("/api/v1/mcp-tool-usage/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "tool": "gc_finding",
                                            "action": "create",
                                            "outcome": "ok",
                                            "durationMs": 150,
                                            "project": "proj1",
                                            "ts": "2026-06-01T10:00:00Z"
                                        }
                                        """))
                .andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(
                com.keplerops.groundcontrol.domain.mcptelemetry.service.RecordMcpToolEventCommand.class);
        verify(telemetryService).recordEvent(captor.capture());
        var cmd = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("gc_finding", cmd.tool());
        org.junit.jupiter.api.Assertions.assertEquals("create", cmd.action());
        org.junit.jupiter.api.Assertions.assertEquals("ok", cmd.outcome());
        org.junit.jupiter.api.Assertions.assertEquals(150L, cmd.durationMs());
        org.junit.jupiter.api.Assertions.assertEquals("proj1", cmd.project());
        org.junit.jupiter.api.Assertions.assertEquals(Instant.parse("2026-06-01T10:00:00Z"), cmd.eventTs());
    }

    @Test
    void postEventWithMissingToolReturns422() throws Exception {
        mockMvc.perform(
                        post("/api/v1/mcp-tool-usage/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "outcome": "ok",
                                            "durationMs": 42,
                                            "ts": "2026-06-01T12:00:00Z"
                                        }
                                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void postEventWithNegativeDurationReturns422() throws Exception {
        mockMvc.perform(
                        post("/api/v1/mcp-tool-usage/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "tool": "gc_query",
                                            "outcome": "ok",
                                            "durationMs": -1,
                                            "ts": "2026-06-01T12:00:00Z"
                                        }
                                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void postEventWithBlankToolReturns422() throws Exception {
        mockMvc.perform(
                        post("/api/v1/mcp-tool-usage/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "tool": "",
                                            "outcome": "ok",
                                            "durationMs": 10,
                                            "ts": "2026-06-01T12:00:00Z"
                                        }
                                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void postEventWithMissingTsReturns422() throws Exception {
        mockMvc.perform(
                        post("/api/v1/mcp-tool-usage/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "tool": "gc_query",
                                            "outcome": "ok",
                                            "durationMs": 10
                                        }
                                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/mcp-tool-usage
    // ------------------------------------------------------------------

    @Test
    void getAggregateReturns200WithShape() throws Exception {
        var toolRow = new McpTelemetryService.ToolUsageRow("gc_query", 10L, 0.1, 50L, 200L, 300L);
        var aggregateResult = new McpTelemetryService.AggregateResult(FROM, TO, List.of(toolRow));
        when(telemetryService.aggregate(any(), any())).thenReturn(aggregateResult);

        mockMvc.perform(get("/api/v1/mcp-tool-usage")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from", is(FROM.toString())))
                .andExpect(jsonPath("$.to", is(TO.toString())))
                .andExpect(jsonPath("$.tools", hasSize(1)))
                .andExpect(jsonPath("$.tools[0].tool", is("gc_query")))
                .andExpect(jsonPath("$.tools[0].count", is(10)))
                .andExpect(jsonPath("$.tools[0].errorRate", closeTo(0.1, 1e-6)))
                .andExpect(jsonPath("$.tools[0].p50Ms", is(50)))
                .andExpect(jsonPath("$.tools[0].p95Ms", is(200)))
                .andExpect(jsonPath("$.tools[0].p99Ms", is(300)));
    }

    @Test
    void getAggregateDefaultWindowWhenNoParams() throws Exception {
        var aggregateResult = new McpTelemetryService.AggregateResult(FROM, TO, List.of());
        when(telemetryService.aggregate(any(), any())).thenReturn(aggregateResult);

        mockMvc.perform(get("/api/v1/mcp-tool-usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools", hasSize(0)));

        // Service was called with computed defaults (non-null from/to)
        var captor = ArgumentCaptor.forClass(Instant.class);
        verify(telemetryService).aggregate(captor.capture(), any());
        org.junit.jupiter.api.Assertions.assertNotNull(captor.getValue());
    }

    @Test
    void getAggregateWindowValidationFailure() throws Exception {
        when(telemetryService.aggregate(any(), any()))
                .thenThrow(new DomainValidationException("from must be before to"));

        mockMvc.perform(get("/api/v1/mcp-tool-usage")
                        .param("from", TO.toString())
                        .param("to", FROM.toString()))
                .andExpect(status().isUnprocessableEntity());
    }
}
