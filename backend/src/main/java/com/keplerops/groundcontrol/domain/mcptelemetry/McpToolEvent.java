package com.keplerops.groundcontrol.domain.mcptelemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only operational telemetry record for one MCP tool call.
 *
 * <p>Stores only the closed event shape from the preflight (ADR-059): tool,
 * action, outcome, duration_ms, project, and event_ts. No Envers audit because
 * rows are never mutated; the table is operationally append-only.
 */
@Entity
@Table(name = "mcp_tool_event")
public class McpToolEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String tool;

    @Column(length = 200)
    private String action;

    @Column(nullable = false, length = 100)
    private String outcome;

    @Column(nullable = false)
    private long durationMs;

    @Column(length = 200)
    private String project;

    @Column(nullable = false)
    private Instant eventTs;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected McpToolEvent() {}

    public McpToolEvent(String tool, String action, String outcome, long durationMs, String project, Instant eventTs) {
        this.tool = tool;
        this.action = action;
        this.outcome = outcome;
        this.durationMs = durationMs;
        this.project = project;
        this.eventTs = eventTs;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTool() {
        return tool;
    }

    public String getAction() {
        return action;
    }

    public String getOutcome() {
        return outcome;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getProject() {
        return project;
    }

    public Instant getEventTs() {
        return eventTs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
