package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository.RunRollupRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for workflow-run telemetry reporting (issue #859).
 *
 * <p>Write path: idempotent run upsert keyed by {@code (project, repo, issueNumber, branch)}, phase
 * event append, and manual cost import. Read path: database-side aggregation over a bounded window.
 *
 * <p>This is a reporting read-model, not a workflow engine (ADR-028). It validates the closed,
 * redacted field set: every caller-supplied string is checked for the reserved {@code <!-- gc:}
 * marker sequence so forged-marker text can never round-trip into telemetry, and numeric economics
 * are range-checked.
 */
@Service
public class WorkflowTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTelemetryService.class);

    /** Default look-back when from/to are omitted. */
    public static final int DEFAULT_WINDOW_DAYS = 30;

    /** Maximum allowed aggregation window in days. */
    public static final int MAX_WINDOW_DAYS = 366;

    /** Reserved sequence that opens every {@code gc:} workflow marker; never allowed in stored fields. */
    private static final String RESERVED_MARKER = "<!-- gc:";

    private static final String PROJECT_FIELD = "project";

    private static final int MAX_RUN_LIST_SIZE = 200;

    private final WorkflowRunRepository runRepository;
    private final WorkflowPhaseEventRepository phaseEventRepository;

    public WorkflowTelemetryService(
            WorkflowRunRepository runRepository, WorkflowPhaseEventRepository phaseEventRepository) {
        this.runRepository = runRepository;
        this.phaseEventRepository = phaseEventRepository;
    }

    /**
     * Record (upsert) one workflow run. Re-observing the same {@code (project, repo, issueNumber,
     * branch)} merges the non-null fields of {@code command} onto the existing row, so bridge
     * ingestion and Temporal Visibility can both refine a run without creating duplicates.
     */
    @Transactional
    public WorkflowRun recordRun(RecordWorkflowRunCommand command) {
        requireText(command.project(), PROJECT_FIELD);
        requireText(command.workflowType(), "workflowType");
        if (command.provenance() == null) {
            throw new DomainValidationException("provenance must not be null");
        }
        rejectReservedMarkers(
                command.project(),
                command.repo(),
                command.branch(),
                command.workflowType(),
                command.runtimeDriver(),
                command.provider(),
                command.model(),
                command.costCurrency());
        if (command.requirementUids() != null) {
            command.requirementUids().forEach(uid -> rejectReservedMarkers(uid));
        }
        validateEconomics(
                command.modelInvocationCount(), command.wallClockMinutes(), command.costProxy(), command.tokenUsage());

        var existing = runRepository.findRunForUpsert(
                command.project(), command.repo(), command.issueNumber(), command.branch());
        WorkflowRun saved;
        if (existing.isPresent()) {
            var run = existing.get();
            applyRunCommand(run, command);
            saved = runRepository.save(run);
        } else {
            var run = new WorkflowRun(command.project(), command.workflowType(), command.provenance());
            applyRunCommand(run, command);
            try {
                // saveAndFlush so a unique-key violation surfaces here, not at commit. The UNIQUE
                // NULLS NOT DISTINCT index on (project, repo, issue_number, branch) is the backstop
                // that stops two concurrent observations from both missing the SELECT above and
                // inserting duplicate rows (which would overcount the reporting aggregate).
                saved = runRepository.saveAndFlush(run);
            } catch (DataIntegrityViolationException race) {
                // A concurrent observation won the insert race for the same key; the unique index
                // rejected the duplicate. Surface a retryable conflict — a retry takes the update
                // path above against the now-existing row, so no duplicate is ever created.
                throw new ConflictException(
                        "Workflow run already recorded for this (project, repo, issue, branch); retry to update it");
            }
        }
        log.info(
                "workflow_run_recorded: project={} issue={} branch={} state={} provenance={}",
                saved.getProject(),
                saved.getIssueNumber(),
                saved.getBranch(),
                saved.getFinalState(),
                saved.getProvenance());
        return loadRequirementUids(saved);
    }

    /** Append one phase/gate event to an existing run. */
    @Transactional
    public WorkflowPhaseEvent recordPhaseEvent(RecordPhaseEventCommand command) {
        if (command.runId() == null) {
            throw new DomainValidationException("runId must not be null");
        }
        requireText(command.project(), PROJECT_FIELD);
        requireText(command.phase(), "phase");
        if (command.eventType() == null) {
            throw new DomainValidationException("eventType must not be null");
        }
        if (command.occurredAt() == null) {
            throw new DomainValidationException("occurredAt must not be null");
        }
        if (command.provenance() == null) {
            throw new DomainValidationException("provenance must not be null");
        }
        rejectReservedMarkers(command.phase(), command.outcome());
        if (command.durationMs() != null && command.durationMs() < 0) {
            throw new DomainValidationException("durationMs must not be negative");
        }
        if (command.cycleIndex() != null && command.cycleIndex() < 0) {
            throw new DomainValidationException("cycleIndex must not be negative");
        }
        var run = runRepository
                .findByIdAndProject(command.runId(), command.project())
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + command.runId()));
        var event = new WorkflowPhaseEvent(
                run.getId(),
                run.getProject(),
                command.phase(),
                command.eventType(),
                command.occurredAt(),
                command.durationMs(),
                command.provenance());
        event.setCycleIndex(command.cycleIndex());
        event.setOutcome(command.outcome());
        return phaseEventRepository.save(event);
    }

    /** Import manual economics for an existing run; only non-null fields are applied. */
    @Transactional
    public WorkflowRun importCost(ImportRunCostCommand command) {
        if (command.runId() == null) {
            throw new DomainValidationException("runId must not be null");
        }
        requireText(command.project(), PROJECT_FIELD);
        rejectReservedMarkers(command.provider(), command.model(), command.costCurrency());
        validateEconomics(
                command.modelInvocationCount(), command.wallClockMinutes(), command.costProxy(), command.tokenUsage());
        var run = runRepository
                .findByIdAndProject(command.runId(), command.project())
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + command.runId()));
        if (command.provider() != null) {
            run.setProvider(command.provider());
        }
        if (command.model() != null) {
            run.setModel(command.model());
        }
        if (command.modelInvocationCount() != null) {
            run.setModelInvocationCount(command.modelInvocationCount());
        }
        if (command.wallClockMinutes() != null) {
            run.setWallClockMinutes(command.wallClockMinutes());
        }
        if (command.costProxy() != null) {
            run.setCostProxy(command.costProxy());
        }
        if (command.costCurrency() != null) {
            run.setCostCurrency(command.costCurrency());
        }
        if (command.tokenUsage() != null) {
            run.setTokenUsage(command.tokenUsage());
        }
        return loadRequirementUids(runRepository.save(run));
    }

    /** Recent runs for one project, newest first (bounded). */
    @Transactional(readOnly = true)
    public List<WorkflowRun> listRuns(String project, int limit) {
        int bounded = Math.clamp(limit, 1, MAX_RUN_LIST_SIZE);
        var runs = runRepository.findByProjectOrderByCreatedAtDesc(project, PageRequest.of(0, bounded));
        runs.forEach(WorkflowTelemetryService::loadRequirementUids);
        return runs;
    }

    /**
     * Aggregate runs and phase events over {@code filter}. The grouping, counting, percentile, and
     * sum math all run in the database; the service maps the already-aggregated rows and derives the
     * cost-per-outcome ratios.
     */
    @Transactional(readOnly = true)
    public RunAggregate aggregate(WorkflowRunFilter filter) {
        validateWindow(filter.from(), filter.to());
        rejectReservedMarkers(
                filter.project(), filter.repo(), filter.workflowType(), filter.runtime(), filter.requirement());

        RunRollupRow row = runRepository.aggregateRuns(
                filter.from(),
                filter.to(),
                filter.project(),
                filter.repo(),
                filter.workflowType(),
                filter.runtime(),
                filter.outcome() == null ? null : filter.outcome().name(),
                filter.requirement());

        // Apply the SAME filters to phase hot-spots as to the run rollup, so the response is one
        // coherent run population rather than mixing run totals for one filter with phase events
        // from every run in the window (issue #859 review finding).
        List<PhaseHotspot> hotspots = new ArrayList<>();
        for (var h : phaseEventRepository.aggregatePhaseHotspots(
                filter.from(),
                filter.to(),
                filter.project(),
                filter.repo(),
                filter.runtime(),
                filter.workflowType(),
                filter.outcome() == null ? null : filter.outcome().name(),
                filter.requirement())) {
            hotspots.add(new PhaseHotspot(
                    h.getPhase(),
                    h.getEventCount(),
                    h.getFailedCount(),
                    h.getEscalatedCount(),
                    h.getP50Ms(),
                    h.getP95Ms(),
                    h.getMaxCycleIndex()));
        }

        return new RunAggregate(
                filter.from(),
                filter.to(),
                row.getTotalRuns(),
                row.getMergedRuns(),
                row.getClosedRuns(),
                row.getActiveRuns(),
                row.getEscalatedRuns(),
                row.getAbandonedRuns(),
                row.getSupersededRuns(),
                row.getCycleTimeP50Min(),
                row.getCycleTimeP95Min(),
                row.getCycleTimeP99Min(),
                row.getTotalCostProxy(),
                row.getMergedCostProxy(),
                row.getClosedCostProxy(),
                perRun(row.getMergedCostProxy(), row.getMergedRuns()),
                perRun(row.getClosedCostProxy(), row.getClosedRuns()),
                row.getTotalModelInvocations(),
                row.getTotalWallClockMinutes(),
                row.getTotalTokenUsage(),
                hotspots);
    }

    private static void applyRunCommand(WorkflowRun run, RecordWorkflowRunCommand command) {
        // Merge semantics: apply each non-null field of the observation onto the run. setIfPresent
        // keeps this a flat data-driven mapping rather than a long if-chain.
        setIfPresent(command.repo(), run::setRepo);
        setIfPresent(command.issueNumber(), run::setIssueNumber);
        setIfPresent(command.prNumber(), run::setPrNumber);
        setIfPresent(command.branch(), run::setBranch);
        setIfPresent(command.runtimeDriver(), run::setRuntimeDriver);
        setIfPresent(command.startedAt(), run::setStartedAt);
        setIfPresent(command.endedAt(), run::setEndedAt);
        setIfPresent(command.finalState(), run::setFinalState);
        setIfPresent(command.outcome(), run::setOutcome);
        setIfPresent(command.provenance(), run::setProvenance);
        setIfPresent(command.provider(), run::setProvider);
        setIfPresent(command.model(), run::setModel);
        setIfPresent(command.modelInvocationCount(), run::setModelInvocationCount);
        setIfPresent(command.wallClockMinutes(), run::setWallClockMinutes);
        setIfPresent(command.costProxy(), run::setCostProxy);
        setIfPresent(command.costCurrency(), run::setCostCurrency);
        setIfPresent(command.tokenUsage(), run::setTokenUsage);
        var uids = command.requirementUids();
        if (uids != null && !uids.isEmpty()) {
            run.setRequirementUids(uids);
        }
    }

    private static <T> void setIfPresent(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Force the LAZY {@code requirementUids} {@code @ElementCollection} to initialize inside the
     * transaction. With {@code spring.jpa.open-in-view=false} the controller maps the entity to a DTO
     * after the tx closes; touching the collection here avoids a {@code LazyInitializationException}
     * on serialization (issue #859 review finding). The newly-created run path holds an in-memory set,
     * so this is a no-op there; it matters for the fetched update/list paths.
     */
    private static WorkflowRun loadRequirementUids(WorkflowRun run) {
        // The accessor builds a defensive copy, which iterates (and thereby initializes) the LAZY
        // set inside the tx; consuming the count keeps the call from being flagged as an ignored
        // return without changing behaviour.
        int loaded = run.getRequirementUids().size();
        log.trace("workflow_run_requirement_uids_loaded: count={}", loaded);
        return run;
    }

    private static BigDecimal perRun(BigDecimal total, long count) {
        if (total == null || count <= 0) {
            return null;
        }
        return total.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
    }

    private static void rejectReservedMarkers(String... values) {
        for (String value : values) {
            if (value != null && value.contains(RESERVED_MARKER)) {
                throw new DomainValidationException(
                        "field must not contain a reserved '" + RESERVED_MARKER + "' marker");
            }
        }
    }

    private static void validateEconomics(
            Integer modelInvocationCount, Integer wallClockMinutes, BigDecimal costProxy, Long tokenUsage) {
        if (modelInvocationCount != null && modelInvocationCount < 0) {
            throw new DomainValidationException("modelInvocationCount must not be negative");
        }
        if (wallClockMinutes != null && wallClockMinutes < 0) {
            throw new DomainValidationException("wallClockMinutes must not be negative");
        }
        if (costProxy != null && costProxy.signum() < 0) {
            throw new DomainValidationException("costProxy must not be negative");
        }
        if (tokenUsage != null && tokenUsage < 0) {
            throw new DomainValidationException("tokenUsage must not be negative");
        }
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

    /** Aggregate reporting result over a scoped window. Cost-per-run ratios are null when the outcome count is zero. */
    public record RunAggregate(
            Instant from,
            Instant to,
            long totalRuns,
            long mergedRuns,
            long closedRuns,
            long activeRuns,
            long escalatedRuns,
            long abandonedRuns,
            long supersededRuns,
            Double cycleTimeP50Min,
            Double cycleTimeP95Min,
            Double cycleTimeP99Min,
            BigDecimal totalCostProxy,
            BigDecimal mergedCostProxy,
            BigDecimal closedCostProxy,
            BigDecimal costProxyPerMergedRun,
            BigDecimal costProxyPerClosedRun,
            long totalModelInvocations,
            long totalWallClockMinutes,
            long totalTokenUsage,
            List<PhaseHotspot> phaseHotspots) {}

    /** Per-phase hot-spot row: counts, failed/escalation counts, p50/p95 duration, and max cycle index. */
    public record PhaseHotspot(
            String phase,
            long eventCount,
            long failedCount,
            long escalatedCount,
            Long p50Ms,
            Long p95Ms,
            Integer maxCycleIndex) {}
}
