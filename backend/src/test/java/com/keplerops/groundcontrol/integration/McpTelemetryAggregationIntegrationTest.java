package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.mcptelemetry.McpToolEvent;
import com.keplerops.groundcontrol.domain.mcptelemetry.repository.McpToolEventRepository;
import com.keplerops.groundcontrol.domain.mcptelemetry.service.McpTelemetryService;
import com.keplerops.groundcontrol.domain.mcptelemetry.service.McpTelemetryService.ToolUsageRow;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the database-side aggregation in {@code McpToolEventRepository.aggregateByEventTsBetween}
 * against real Postgres: {@code COUNT(*) FILTER}, {@code percentile_disc}, {@code GROUP BY}, and the
 * {@code [from, to)} window bound. The unit test mocks the repository, so this is the only coverage
 * of the native query and the percentile semantics it relies on.
 */
class McpTelemetryAggregationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private McpToolEventRepository repository;

    @Autowired
    private McpTelemetryService service;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");
    private static final Instant IN_WINDOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant BEFORE_WINDOW = Instant.parse("2026-05-01T12:00:00Z");

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    void aggregatesCountErrorRateAndPercentilesPerToolInTheDatabase() {
        // gc_query: 3 calls in-window (durations 50/100/200), one of them an error.
        repository.save(new McpToolEvent("gc_query", "list", "ok", 100L, "p1", IN_WINDOW));
        repository.save(new McpToolEvent("gc_query", "list", "ok", 200L, "p2", IN_WINDOW));
        repository.save(new McpToolEvent("gc_query", "list", "not_found", 50L, "p1", IN_WINDOW));
        // gc_finding: 1 call in-window, no errors.
        repository.save(new McpToolEvent("gc_finding", null, "ok", 10L, "p1", IN_WINDOW));
        // Outside the window: must be excluded from the aggregate.
        repository.save(new McpToolEvent("gc_query", "list", "error", 9999L, "p1", BEFORE_WINDOW));

        var result = service.aggregate(FROM, TO);

        assertThat(result.tools()).hasSize(2);

        ToolUsageRow gcQuery = result.tools().stream()
                .filter(r -> "gc_query".equals(r.tool()))
                .findFirst()
                .orElseThrow();
        // The out-of-window error row is excluded: count is 3, not 4.
        assertThat(gcQuery.count()).isEqualTo(3);
        assertThat(gcQuery.errorRate()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        // percentile_disc over {50,100,200}: p50 -> 100, p95/p99 -> 200.
        assertThat(gcQuery.p50Ms()).isEqualTo(100L);
        assertThat(gcQuery.p95Ms()).isEqualTo(200L);
        assertThat(gcQuery.p99Ms()).isEqualTo(200L);

        ToolUsageRow gcFinding = result.tools().stream()
                .filter(r -> "gc_finding".equals(r.tool()))
                .findFirst()
                .orElseThrow();
        assertThat(gcFinding.count()).isEqualTo(1);
        assertThat(gcFinding.errorRate()).isZero();
        assertThat(gcFinding.p50Ms()).isEqualTo(10L);
    }

    @Test
    void returnsEmptyWhenNoEventsInWindow() {
        repository.save(new McpToolEvent("gc_query", "list", "ok", 100L, "p1", BEFORE_WINDOW));

        var result = service.aggregate(FROM, TO);

        assertThat(result.tools()).isEmpty();
    }
}
