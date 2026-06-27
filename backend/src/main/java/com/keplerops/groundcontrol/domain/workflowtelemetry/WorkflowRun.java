package com.keplerops.groundcontrol.domain.workflowtelemetry;

import com.keplerops.groundcontrol.domain.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One workflow run (issue #859): the run-level reporting projection for a single {@code /implement}
 * (or future Temporal-backed) execution against an issue/branch/PR/repo.
 *
 * <p>This is a correlation/projection surface, not the workflow engine (ADR-028): Temporal
 * Visibility becomes the source of truth once GC-O009 owns execution end to end. The row is mutable
 * because ingestion is idempotent — re-observing a run (a later phase marker, the merge outcome,
 * a manual cost import) updates the same row, keyed by {@code (project, repo, issueNumber, branch)}.
 *
 * <p>Closed, redacted field set: only safe correlation and economics scalars are stored. Prompts,
 * completions, bearer tokens, provider/GitHub keys, and raw reviewer payloads are never persisted.
 */
@Entity
@Table(name = "workflow_run")
public class WorkflowRun extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String project;

    @Column(length = 200)
    private String repo;

    private Integer issueNumber;

    private Integer prNumber;

    @Column(length = 300)
    private String branch;

    @Column(nullable = false, length = 100)
    private String workflowType;

    @Column(length = 100)
    private String runtimeDriver;

    @ElementCollection
    @CollectionTable(name = "workflow_run_requirement_uid", joinColumns = @JoinColumn(name = "run_id"))
    @Column(name = "requirement_uid", length = 100)
    private Set<String> requirementUids = new LinkedHashSet<>();

    private Instant startedAt;

    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WorkflowRunState finalState = WorkflowRunState.RUNNING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WorkflowRunOutcome outcome = WorkflowRunOutcome.NONE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TelemetryProvenance provenance;

    @Column(length = 100)
    private String provider;

    @Column(length = 200)
    private String model;

    private Integer modelInvocationCount;

    private Integer wallClockMinutes;

    @Column(precision = 14, scale = 4)
    private BigDecimal costProxy;

    @Column(length = 10)
    private String costCurrency;

    private Long tokenUsage;

    protected WorkflowRun() {}

    public WorkflowRun(String project, String workflowType, TelemetryProvenance provenance) {
        this.project = project;
        this.workflowType = workflowType;
        this.provenance = provenance;
    }

    public String getProject() {
        return project;
    }

    public String getRepo() {
        return repo;
    }

    public void setRepo(String repo) {
        this.repo = repo;
    }

    public Integer getIssueNumber() {
        return issueNumber;
    }

    public void setIssueNumber(Integer issueNumber) {
        this.issueNumber = issueNumber;
    }

    public Integer getPrNumber() {
        return prNumber;
    }

    public void setPrNumber(Integer prNumber) {
        this.prNumber = prNumber;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public String getRuntimeDriver() {
        return runtimeDriver;
    }

    public void setRuntimeDriver(String runtimeDriver) {
        this.runtimeDriver = runtimeDriver;
    }

    public Set<String> getRequirementUids() {
        // Defensive copy: callers must not mutate the run's requirement set through the accessor.
        // Hibernate uses field access (the @Id is on a field in BaseEntity), so it reads the field
        // directly and never relies on this getter for dirty tracking.
        return Set.copyOf(requirementUids);
    }

    public void setRequirementUids(Set<String> requirementUids) {
        this.requirementUids = requirementUids == null ? new LinkedHashSet<>() : new LinkedHashSet<>(requirementUids);
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public WorkflowRunState getFinalState() {
        return finalState;
    }

    public void setFinalState(WorkflowRunState finalState) {
        this.finalState = finalState;
    }

    public WorkflowRunOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(WorkflowRunOutcome outcome) {
        this.outcome = outcome;
    }

    public TelemetryProvenance getProvenance() {
        return provenance;
    }

    public void setProvenance(TelemetryProvenance provenance) {
        this.provenance = provenance;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getModelInvocationCount() {
        return modelInvocationCount;
    }

    public void setModelInvocationCount(Integer modelInvocationCount) {
        this.modelInvocationCount = modelInvocationCount;
    }

    public Integer getWallClockMinutes() {
        return wallClockMinutes;
    }

    public void setWallClockMinutes(Integer wallClockMinutes) {
        this.wallClockMinutes = wallClockMinutes;
    }

    public BigDecimal getCostProxy() {
        return costProxy;
    }

    public void setCostProxy(BigDecimal costProxy) {
        this.costProxy = costProxy;
    }

    public String getCostCurrency() {
        return costCurrency;
    }

    public void setCostCurrency(String costCurrency) {
        this.costCurrency = costCurrency;
    }

    public Long getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(Long tokenUsage) {
        this.tokenUsage = tokenUsage;
    }
}
