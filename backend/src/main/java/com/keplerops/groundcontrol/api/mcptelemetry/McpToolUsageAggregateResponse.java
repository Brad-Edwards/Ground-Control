package com.keplerops.groundcontrol.api.mcptelemetry;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/v1/mcp-tool-usage}.
 */
public record McpToolUsageAggregateResponse(Instant from, Instant to, List<ToolUsageRow> tools) {

    /**
     * Per-tool usage statistics for the requested window.
     */
    public record ToolUsageRow(String tool, long count, double errorRate, long p50Ms, long p95Ms, long p99Ms) {}
}
