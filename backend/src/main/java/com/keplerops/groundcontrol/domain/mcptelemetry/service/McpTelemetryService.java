package com.keplerops.groundcontrol.domain.mcptelemetry.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.mcptelemetry.McpToolEvent;
import com.keplerops.groundcontrol.domain.mcptelemetry.repository.McpToolEventRepository;
import com.keplerops.groundcontrol.domain.mcptelemetry.repository.McpToolEventRepository.ToolAggregateRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for MCP tool usage telemetry.
 *
 * <p>Provides two operations:
 * <ul>
 *   <li>{@link #record(RecordMcpToolEventCommand)} — persist one event (transactional write).
 *   <li>{@link #aggregate(Instant, Instant)} — read-only aggregation over a time window.
 * </ul>
 *
 * <p>Window policy (named constants here — the single seam for default/max):
 * <ul>
 *   <li>Default window when the caller omits from/to: last {@link #DEFAULT_WINDOW_HOURS} hours.
 *   <li>Maximum allowed window: {@link #MAX_WINDOW_DAYS} days.
 * </ul>
 */
@Service
public class McpTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(McpTelemetryService.class);

    /** Default look-back when from/to are omitted. */
    public static final int DEFAULT_WINDOW_HOURS = 24;

    /** Maximum allowed aggregation window in days. */
    public static final int MAX_WINDOW_DAYS = 31;

    private final McpToolEventRepository repository;

    public McpTelemetryService(McpToolEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist one MCP tool call event.
     */
    @Transactional
    public void record(RecordMcpToolEventCommand command) {
        var event = new McpToolEvent(
                command.tool(),
                command.action(),
                command.outcome(),
                command.durationMs(),
                command.project(),
                command.eventTs());
        repository.save(event);
        log.info(
                "mcp_tool_event_recorded: tool={} outcome={} duration_ms={}",
                command.tool(),
                command.outcome(),
                command.durationMs());
    }

    /**
     * Aggregate tool usage statistics over [from, to).
     *
     * <p>Validation: both timestamps required, from < to, window ≤
     * {@link #MAX_WINDOW_DAYS} days.
     *
     * @param from window start (inclusive); must not be null
     * @param to   window end (exclusive); must not be null
     * @return aggregation result for each tool that emitted at least one event
     */
    @Transactional(readOnly = true)
    public AggregateResult aggregate(Instant from, Instant to) {
        validateWindow(from, to);

        // The database does the grouping, counting, and percentile math (see
        // McpToolEventRepository.aggregateByEventTsBetween). The service only maps the
        // already-aggregated rows and derives the error rate; it never materializes the
        // raw event window in memory.
        List<ToolAggregateRow> rows = repository.aggregateByEventTsBetween(from, to);

        List<ToolUsageRow> toolRows = new ArrayList<>(rows.size());
        for (ToolAggregateRow row : rows) {
            long total = row.getCount();
            long errors = row.getErrorCount();
            double errorRate = total == 0 ? 0.0 : (double) errors / total;
            toolRows.add(
                    new ToolUsageRow(row.getTool(), total, errorRate, row.getP50Ms(), row.getP95Ms(), row.getP99Ms()));
        }

        return new AggregateResult(from, to, toolRows);
    }

    private static void validateWindow(Instant from, Instant to) {
        if (from == null) {
            throw new DomainValidationException("from must not be null");
        }
        if (to == null) {
            throw new DomainValidationException("to must not be null");
        }
        if (!from.isBefore(to)) {
            throw new DomainValidationException("from must be before to");
        }
        long days = Duration.between(from, to).toDays();
        if (days > MAX_WINDOW_DAYS) {
            throw new DomainValidationException(
                    "time window must not exceed " + MAX_WINDOW_DAYS + " days (requested " + days + " days)");
        }
    }

    /**
     * Aggregate result carrying the resolved window and per-tool rows.
     */
    public record AggregateResult(Instant from, Instant to, List<ToolUsageRow> tools) {}

    /**
     * Per-tool usage statistics for one aggregation window.
     */
    public record ToolUsageRow(String tool, long count, double errorRate, long p50Ms, long p95Ms, long p99Ms) {}
}
