package com.keplerops.groundcontrol.api.mcptelemetry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Request body for {@code POST /api/v1/mcp-tool-usage/events}.
 *
 * <p>This is the closed capture DTO and info-disclosure boundary: only the
 * allowlisted fields from ADR-059 are accepted. Unknown fields are rejected
 * by the standard Spring Boot deserialization configuration.
 */
public record RecordMcpToolEventRequest(
        @NotBlank @Size(max = 200) String tool,
        @Size(max = 200) String action,
        @NotBlank @Size(max = 100) String outcome,
        @NotNull @PositiveOrZero Long durationMs,
        @Size(max = 200) String project,
        @NotNull Instant ts) {}
