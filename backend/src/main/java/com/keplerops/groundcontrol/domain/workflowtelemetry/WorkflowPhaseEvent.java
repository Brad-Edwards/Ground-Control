package com.keplerops.groundcontrol.domain.workflowtelemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One phase/gate event for a workflow run (issue #859): preflight, plan, completion gate, a Codex
 * review cycle, CI, SonarCloud, a status transition, an escalation, etc.
 *
 * <p>Append-only operational telemetry — rows are never mutated after insert, so (like
 * {@code McpToolEvent} under ADR-059) there is no Envers audit. {@code project} is denormalized from
 * the parent run so phase aggregates scope and index without a join. {@code phase} is a stable
 * machine identifier (never user-visible prose) so reporting keys stay stable across UI wording.
 */
@Entity
@Table(name = "workflow_phase_event")
public class WorkflowPhaseEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID runId;

    @Column(nullable = false, length = 200)
    private String project;

    @Column(nullable = false, length = 100)
    private String phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PhaseEventType eventType;

    private Integer cycleIndex;

    @Column(nullable = false)
    private Instant occurredAt;

    private Long durationMs;

    @Column(length = 100)
    private String outcome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TelemetryProvenance provenance;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkflowPhaseEvent() {}

    public WorkflowPhaseEvent(
            UUID runId,
            String project,
            String phase,
            PhaseEventType eventType,
            Integer cycleIndex,
            Instant occurredAt,
            Long durationMs,
            String outcome,
            TelemetryProvenance provenance) {
        this.runId = runId;
        this.project = project;
        this.phase = phase;
        this.eventType = eventType;
        this.cycleIndex = cycleIndex;
        this.occurredAt = occurredAt;
        this.durationMs = durationMs;
        this.outcome = outcome;
        this.provenance = provenance;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getProject() {
        return project;
    }

    public String getPhase() {
        return phase;
    }

    public PhaseEventType getEventType() {
        return eventType;
    }

    public Integer getCycleIndex() {
        return cycleIndex;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getOutcome() {
        return outcome;
    }

    public TelemetryProvenance getProvenance() {
        return provenance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
