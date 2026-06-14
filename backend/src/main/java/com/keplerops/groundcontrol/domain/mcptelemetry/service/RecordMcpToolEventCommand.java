package com.keplerops.groundcontrol.domain.mcptelemetry.service;

import java.time.Instant;

/**
 * Immutable command to record one MCP tool call event.
 * Carries only the closed event fields from the ADR-059 shape.
 */
public record RecordMcpToolEventCommand(
        String tool, String action, String outcome, long durationMs, String project, Instant eventTs) {}
