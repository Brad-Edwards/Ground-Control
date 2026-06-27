package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-R001/R003/F003/F036/N007/N011 — ADR-063 / ADR-064.
 *
 * <p>Project-scoped execution aggregate for one pass through the research
 * lifecycle. Sibling of {@link ResearchIntake} (which holds project-level
 * defaults): a run snapshots the run-driving values at start so later intake
 * edits never rewrite an active or completed run.
 *
 * <p>{@code currentStage} and {@code status} are separate axes (ADR-063 §3).
 * Stage transitions, gate policy, and the prerequisite matrix are owned by the
 * service; this entity guards only the status-transition graph and exposes
 * bounded summary fields for observability (ADR-064). The {@code project}
 * reference is {@code @NotAudited}; its FK is intentionally absent from the
 * audit shadow.
 */
@Entity
@Audited
@Table(name = "research_run", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class ResearchRun extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 50)
    private String uid;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 40)
    private ResearchRunStage currentStage = ResearchRunStage.METHODOLOGY_SELECTION;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResearchRunStatus status = ResearchRunStatus.IN_PROGRESS;

    @Enumerated(EnumType.STRING)
    @Column(name = "autonomy_level", nullable = false, length = 20)
    private AutonomyLevel autonomyLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "intended_output", length = 40)
    private IntendedOutput intendedOutput;

    @Column(name = "owner_actor", length = 200)
    private String ownerActor;

    // Budget caps snapshotted from ResearchIntake at start (nullable = no cap).
    @Column(name = "budget_tokens")
    private Long budgetTokens;

    @Column(name = "budget_wall_clock_minutes")
    private Integer budgetWallClockMinutes;

    @Column(name = "budget_cost_usd_micros")
    private Long budgetCostUsdMicros;

    // Observed usage — separate from caps (ADR-064 §7). Accumulated via recordUsage.
    @Column(name = "observed_tokens", nullable = false)
    private long observedTokens = 0L;

    @Column(name = "observed_cost_usd_micros", nullable = false)
    private long observedCostUsdMicros = 0L;

    // Bounded source-disposition summary counts (ADR-064 §5). Accepted with
    // search/screening/charting artifacts; never derived from workspace files.
    @Column(name = "candidate_sources", nullable = false)
    private int candidateSources = 0;

    @Column(name = "screened_included", nullable = false)
    private int screenedIncluded = 0;

    @Column(name = "screened_excluded", nullable = false)
    private int screenedExcluded = 0;

    @Column(name = "charted_full_text", nullable = false)
    private int chartedFullText = 0;

    @Column(name = "access_gaps", nullable = false)
    private int accessGaps = 0;

    // Bounded last-error observation (ADR-064 §6). No stack traces / raw content.
    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_error_class", length = 40)
    private String lastErrorClass;

    @Column(name = "last_error_summary", length = 500)
    private String lastErrorSummary;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    protected ResearchRun() {
        // JPA
    }

    public ResearchRun(Project project, String uid, AutonomyLevel autonomyLevel) {
        if (project == null) {
            throw new DomainValidationException("Project must not be null", "invalid_research_run", Map.of());
        }
        if (uid == null || uid.isBlank()) {
            throw new DomainValidationException("UID must not be blank", "invalid_research_run", Map.of());
        }
        if (autonomyLevel == null) {
            throw new DomainValidationException("Autonomy level must not be null", "invalid_research_run", Map.of());
        }
        this.project = project;
        this.uid = uid;
        this.autonomyLevel = autonomyLevel;
    }

    /** Guarded run-status transition; rejects illegal arcs (ADR-063 §3). */
    public void transitionStatus(ResearchRunStatus newStatus) {
        if (newStatus == null || !status.canTransitionTo(newStatus)) {
            throw new DomainValidationException(
                    "Cannot transition research run status from " + status + " to " + newStatus,
                    "research_run_invalid_status_transition",
                    Map.of("current", status.name(), "requested", String.valueOf(newStatus)));
        }
        this.status = newStatus;
    }

    /**
     * Move the run into {@code stage}. Prerequisite-matrix and gate validation
     * are enforced by the service before this is called; the entity only records
     * the advance and ensures the run is active.
     */
    public void advanceToStage(ResearchRunStage stage) {
        if (stage == null) {
            throw new DomainValidationException("Target stage must not be null", "invalid_research_run", Map.of());
        }
        this.currentStage = stage;
    }

    public void recordError(String code, String errorClass, String summary, Instant at) {
        this.lastErrorCode = code;
        this.lastErrorClass = errorClass;
        this.lastErrorSummary = summary;
        this.lastErrorAt = at;
    }

    public void addUsage(long tokens, long costUsdMicros) {
        this.observedTokens += Math.max(0L, tokens);
        this.observedCostUsdMicros += Math.max(0L, costUsdMicros);
    }

    public Project getProject() {
        return project;
    }

    public String getUid() {
        return uid;
    }

    public ResearchRunStage getCurrentStage() {
        return currentStage;
    }

    public ResearchRunStatus getStatus() {
        return status;
    }

    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    public IntendedOutput getIntendedOutput() {
        return intendedOutput;
    }

    public void setIntendedOutput(IntendedOutput intendedOutput) {
        this.intendedOutput = intendedOutput;
    }

    public String getOwnerActor() {
        return ownerActor;
    }

    public void setOwnerActor(String ownerActor) {
        this.ownerActor = ownerActor;
    }

    public Long getBudgetTokens() {
        return budgetTokens;
    }

    public void setBudgetTokens(Long budgetTokens) {
        this.budgetTokens = budgetTokens;
    }

    public Integer getBudgetWallClockMinutes() {
        return budgetWallClockMinutes;
    }

    public void setBudgetWallClockMinutes(Integer budgetWallClockMinutes) {
        this.budgetWallClockMinutes = budgetWallClockMinutes;
    }

    public Long getBudgetCostUsdMicros() {
        return budgetCostUsdMicros;
    }

    public void setBudgetCostUsdMicros(Long budgetCostUsdMicros) {
        this.budgetCostUsdMicros = budgetCostUsdMicros;
    }

    public long getObservedTokens() {
        return observedTokens;
    }

    public long getObservedCostUsdMicros() {
        return observedCostUsdMicros;
    }

    public int getCandidateSources() {
        return candidateSources;
    }

    public void setCandidateSources(int candidateSources) {
        this.candidateSources = Math.max(0, candidateSources);
    }

    public int getScreenedIncluded() {
        return screenedIncluded;
    }

    public void setScreenedIncluded(int screenedIncluded) {
        this.screenedIncluded = Math.max(0, screenedIncluded);
    }

    public int getScreenedExcluded() {
        return screenedExcluded;
    }

    public void setScreenedExcluded(int screenedExcluded) {
        this.screenedExcluded = Math.max(0, screenedExcluded);
    }

    public int getChartedFullText() {
        return chartedFullText;
    }

    public void setChartedFullText(int chartedFullText) {
        this.chartedFullText = Math.max(0, chartedFullText);
    }

    public int getAccessGaps() {
        return accessGaps;
    }

    public void setAccessGaps(int accessGaps) {
        this.accessGaps = Math.max(0, accessGaps);
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public String getLastErrorClass() {
        return lastErrorClass;
    }

    public String getLastErrorSummary() {
        return lastErrorSummary;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }

    public void setStoppedAt(Instant stoppedAt) {
        this.stoppedAt = stoppedAt;
    }
}
