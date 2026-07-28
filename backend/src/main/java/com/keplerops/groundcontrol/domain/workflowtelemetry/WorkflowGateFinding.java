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
 * One finding a station attempt observed (issue #1355, ADR-090).
 *
 * <p>A subordinate process observation linked to the terminal event of its attempt — deliberately
 * not the product {@code Finding} aggregate, which models retained GRC findings with their own
 * lifecycle, links, and evidence semantics. A review or scanner observation is workflow
 * measurement, not compliance evidence.
 *
 * <p>The row carries bounded facts only: no title, body, remediation text, file path, line number,
 * raw tool output, or stack trace. The ADR-029 issue thread remains the narrative record, and the
 * projection must not become a rival to it or a place source content leaks into reporting.
 *
 * <p>Correlations are UUID columns rather than {@code @ManyToOne} associations, matching
 * {@link WorkflowPhaseEvent}'s existing style; {@code project} is denormalized from the parent run
 * so finding aggregates scope and index without a join. Envers audits the row because
 * {@code disposition} is the one field that legitimately changes after insert, and its transition
 * history is exactly what a dispute about "was this ever fixed" needs.
 */
@Entity
@Audited
@Table(name = "workflow_gate_finding")
public class WorkflowGateFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID runId;

    @Column(nullable = false, updatable = false)
    private UUID phaseEventId;

    @Column(nullable = false, length = 200)
    private String project;

    /** Authoritative catalogue station id, denormalized so finding aggregates need no join. */
    @Column(nullable = false, length = 100)
    private String stationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FindingSourceKind sourceKind;

    /** The reviewer or detector that produced it, e.g. {@code core}, {@code spotbugs}. */
    @Column(nullable = false, length = 100)
    private String sourceId;

    /**
     * Identity within the parent attempt: a source-provided stable key when one exists, otherwise
     * an opaque deterministic digest of bounded structural fields. Unique per phase event, so a
     * redelivered batch converges instead of appending duplicates.
     */
    @Column(nullable = false, length = 200, updatable = false)
    private String findingKey;

    /**
     * Source-native category — Sonar rule key, SpotBugs pattern, Vale check, policy violation code,
     * CI job/step. Null when the source has none: a one-off review finding has no recurring shape,
     * and a synthetic "uncategorized" would invent a category that does not exist.
     */
    @Column(length = 300)
    private String category;

    /**
     * Source-native severity, preserved exactly. Null when the source does not express one — Codex
     * review findings do not — because a guessed level fabricates a distribution.
     */
    @Column(length = 60)
    private String severity;

    /** The review envelope's own axis ({@code one-off} / {@code class}). Null for detectors. */
    @Column(length = 20)
    private String classification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FindingDisposition disposition;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkflowGateFinding() {}

    /**
     * Required identity and classification. The three optional descriptive fields are set through
     * their setters before persist, keeping the constructor at the repo's 7-parameter limit.
     */
    public WorkflowGateFinding(
            UUID runId,
            UUID phaseEventId,
            String project,
            String stationId,
            FindingSourceKind sourceKind,
            String sourceId,
            String findingKey) {
        this.runId = runId;
        this.phaseEventId = phaseEventId;
        this.project = project;
        this.stationId = stationId;
        this.sourceKind = sourceKind;
        this.sourceId = sourceId;
        this.findingKey = findingKey;
        this.disposition = FindingDisposition.OPEN;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    /**
     * Move to a terminal disposition.
     *
     * <p>Monotonic and idempotent: re-applying the same terminal value is a no-op so an at-least-once
     * delivery converges, while a conflicting terminal claim is refused rather than silently
     * overwritten — two sources disagreeing about whether something was fixed is a fact worth
     * surfacing, not one to resolve by write order.
     *
     * @return true when this call changed the disposition
     * @throws IllegalStateException when a different terminal disposition is already recorded
     */
    public boolean applyDisposition(FindingDisposition next) {
        if (next == null || next == FindingDisposition.OPEN) {
            throw new IllegalArgumentException("disposition transition must name a terminal value");
        }
        if (this.disposition == next) {
            return false;
        }
        if (this.disposition.isTerminal()) {
            throw new IllegalStateException(
                    "finding already resolved as " + this.disposition + "; refusing to overwrite with " + next);
        }
        this.disposition = next;
        return true;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.occurredAt == null) {
            this.occurredAt = this.createdAt;
        }
        if (this.disposition == null) {
            this.disposition = FindingDisposition.OPEN;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getPhaseEventId() {
        return phaseEventId;
    }

    public String getProject() {
        return project;
    }

    public String getStationId() {
        return stationId;
    }

    public FindingSourceKind getSourceKind() {
        return sourceKind;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getFindingKey() {
        return findingKey;
    }

    public String getCategory() {
        return category;
    }

    public String getSeverity() {
        return severity;
    }

    public String getClassification() {
        return classification;
    }

    public FindingDisposition getDisposition() {
        return disposition;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
