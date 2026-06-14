package com.keplerops.groundcontrol.api.mcptelemetry;

import com.keplerops.groundcontrol.domain.mcptelemetry.service.McpTelemetryService;
import com.keplerops.groundcontrol.domain.mcptelemetry.service.RecordMcpToolEventCommand;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller for MCP tool usage telemetry (ADR-059).
 *
 * <p>Two endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/mcp-tool-usage/events} — capture one event.
 *   <li>{@code GET /api/v1/mcp-tool-usage} — read aggregated statistics.
 * </ul>
 *
 * <p>No repository or domain-entity imports; depends only on the service + DTOs
 * to respect the {@code api/ -> domain/} ArchUnit layering boundary.
 */
@RestController
@RequestMapping("/api/v1/mcp-tool-usage")
public class McpTelemetryController {

    private final McpTelemetryService telemetryService;

    public McpTelemetryController(McpTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    /**
     * Capture one MCP tool call event.
     *
     * <p>Returns 201 on success. Validation failures (missing tool, negative
     * duration, etc.) are handled by {@code GlobalExceptionHandler} →
     * {@code MethodArgumentNotValidException} → 422.
     */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public void recordEvent(@Valid @RequestBody RecordMcpToolEventRequest request) {
        var command = new RecordMcpToolEventCommand(
                request.tool(),
                request.action(),
                request.outcome(),
                request.durationMs(),
                request.project(),
                request.ts());
        telemetryService.record(command);
    }

    /**
     * Aggregate MCP tool usage over a time window.
     *
     * <p>If {@code from} and {@code to} are omitted, defaults to the last
     * {@link McpTelemetryService#DEFAULT_WINDOW_HOURS} hours. Window validation
     * (null from/to, from >= to, window > max) raises
     * {@code DomainValidationException} → 422 via {@code GlobalExceptionHandler}.
     */
    @GetMapping
    public McpToolUsageAggregateResponse aggregate(
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        if (from == null && to == null) {
            to = Instant.now();
            from = to.minus(McpTelemetryService.DEFAULT_WINDOW_HOURS, ChronoUnit.HOURS);
        }
        var result = telemetryService.aggregate(from, to);
        var toolRows = result.tools().stream()
                .map(r -> new McpToolUsageAggregateResponse.ToolUsageRow(
                        r.tool(), r.count(), r.errorRate(), r.p50Ms(), r.p95Ms(), r.p99Ms()))
                .toList();
        return new McpToolUsageAggregateResponse(result.from(), result.to(), toolRows);
    }
}
