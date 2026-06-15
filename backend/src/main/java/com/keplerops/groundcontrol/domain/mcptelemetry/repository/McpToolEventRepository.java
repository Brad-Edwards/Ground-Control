package com.keplerops.groundcontrol.domain.mcptelemetry.repository;

import com.keplerops.groundcontrol.domain.mcptelemetry.McpToolEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpToolEventRepository extends JpaRepository<McpToolEvent, UUID> {

    /**
     * Aggregate per-tool usage over {@code [from, to)} in the database rather than in the
     * service. Grouping, counting, error counting, and percentile computation all run in
     * Postgres so the read scales with the {@code (event_ts, tool)} index instead of
     * materializing every row in the requested window in JVM memory.
     *
     * <p>Latency percentiles use {@code percentile_disc}, which returns an observed sample value
     * (nearest-rank semantics, never an interpolated value). Aliases are quoted so the
     * result-set column labels match the {@link ToolAggregateRow} getter property names exactly.
     */
    @Query(
            value = "SELECT e.tool AS tool,"
                    + " COUNT(*) AS \"count\","
                    + " COUNT(*) FILTER (WHERE e.outcome <> 'ok') AS \"errorCount\","
                    + " percentile_disc(0.5) WITHIN GROUP (ORDER BY e.duration_ms) AS \"p50Ms\","
                    + " percentile_disc(0.95) WITHIN GROUP (ORDER BY e.duration_ms) AS \"p95Ms\","
                    + " percentile_disc(0.99) WITHIN GROUP (ORDER BY e.duration_ms) AS \"p99Ms\""
                    + " FROM mcp_tool_event e"
                    + " WHERE e.event_ts >= :from AND e.event_ts < :to"
                    + " GROUP BY e.tool"
                    + " ORDER BY e.tool",
            nativeQuery = true)
    List<ToolAggregateRow> aggregateByEventTsBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * One already-aggregated row per tool: total call count, error count (outcome other than
     * {@code "ok"}), and p50/p95/p99 latency in milliseconds.
     */
    interface ToolAggregateRow {
        String getTool();

        long getCount();

        long getErrorCount();

        long getP50Ms();

        long getP95Ms();

        long getP99Ms();
    }
}
