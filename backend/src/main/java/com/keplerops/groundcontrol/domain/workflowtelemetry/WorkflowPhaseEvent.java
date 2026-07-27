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
import org.hibernate.envers.Audited;

/**
 * One phase/gate event for a workflow run (issue #859): preflight, plan, completion gate, a Codex
 * review cycle, CI, SonarCloud, a status transition, an escalation, etc.
 *
 * <p>Append-only operational telemetry — rows are never mutated after insert. It participates in
 * Envers because the workflow graph projection records the revision visible to each materialized
 * snapshot (ADR-061 amendment for issue #1311). {@code project} is denormalized from the parent run
 * so phase aggregates scope and index without a join. {@code phase} is a stable machine identifier
 * (never user-visible prose) so reporting keys stay stable across UI wording.
 */
@Entity
@Audited
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

    /**
     * Deterministic identity of the logical fact this row records, unique within the run (issue
     * #1435). Live emission and issue-thread backfill describe the same attempt from two different
     * vantage points; without a shared key the append-only table would hold two copies of it and
     * every per-phase count would be inflated. Timestamp, provenance, and event type are all
     * unstable across those vantage points, so they cannot serve as the key.
     */
    @Column(nullable = false, length = 200)
    private String sourceId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkflowPhaseEvent() {}

    /**
     * Required fields. The two optional fields ({@code cycleIndex}, {@code outcome}) are set via
     * their setters before persist — keeping the constructor at the 7-parameter limit. The row is
     * still effectively append-only: the setters are only used to populate the event before save.
     */
    public WorkflowPhaseEvent(
            UUID runId,
            String project,
            String phase,
            PhaseEventType eventType,
            Instant occurredAt,
            Long durationMs,
            TelemetryProvenance provenance) {
        this.runId = runId;
        this.project = project;
        this.phase = phase;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.durationMs = durationMs;
        this.provenance = provenance;
    }

    public void setCycleIndex(Integer cycleIndex) {
        this.cycleIndex = cycleIndex;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    /**
     * Deterministic identity of a logical phase fact: the station, what happened to it, and which
     * attempt. Shared with the service so the value a caller is deduplicated against is the same one
     * that would be persisted.
     */
    public static String deriveSourceId(String phase, PhaseEventType eventType, Integer cycleIndex) {
        return phase + ":" + eventType.name() + ":" + (cycleIndex == null ? 0 : cycleIndex);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        // The identity is an invariant of the row, not a courtesy of one write path. Deriving it
        // here means any writer produces a deduplicable event instead of tripping the NOT NULL
        // constraint at commit.
        if (this.sourceId == null || this.sourceId.isBlank()) {
            this.sourceId = deriveSourceId(this.phase, this.eventType, this.cycleIndex);
        }
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

    public String getSourceId() {
        return sourceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
