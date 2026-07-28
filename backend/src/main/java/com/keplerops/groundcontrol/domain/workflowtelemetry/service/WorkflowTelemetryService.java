package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository.RunRollupRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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

    private static final String PROJECT_FIELD = "project";

    private static final String RUN_NOT_FOUND = "Workflow run not found: ";

    private static final String PHASE_EVENT_NOT_FOUND = "Workflow phase event not found: ";

    private static final int MAX_RUN_LIST_SIZE = 200;

    /** Bound on the per-run event page; a long-running review loop can accrue many attempts. */
    private static final int MAX_PHASE_EVENT_LIST_SIZE = 500;

    private final WorkflowRunRepository runRepository;
    private final WorkflowPhaseEventRepository phaseEventRepository;
    private final WorkflowMeasurementService measurementService;
    private final ApplicationEventPublisher eventPublisher;

    public WorkflowTelemetryService(
            WorkflowRunRepository runRepository,
            WorkflowPhaseEventRepository phaseEventRepository,
            WorkflowMeasurementService measurementService,
            ApplicationEventPublisher eventPublisher) {
        this.runRepository = runRepository;
        this.phaseEventRepository = phaseEventRepository;
        this.measurementService = measurementService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Record (upsert) one workflow run. Re-observing the same {@code (project, repo, issueNumber,
     * branch)} merges the non-null fields of {@code command} onto the existing row, so repeated
     * bridge ingestion refines a run without creating duplicates.
     */
    @Transactional
    public WorkflowRun recordRun(RecordWorkflowRunCommand command) {
        WorkflowTelemetryValidation.requireText(command.project(), PROJECT_FIELD);
        WorkflowTelemetryValidation.requireText(command.workflowType(), "workflowType");
        if (command.provenance() == null) {
            throw new DomainValidationException("provenance must not be null");
        }
        WorkflowTelemetryValidation.rejectReservedMarkers(
                command.project(),
                command.repo(),
                command.branch(),
                command.workflowType(),
                command.runtimeDriver(),
                command.provider(),
                command.model(),
                command.costCurrency());
        if (command.requirementUids() != null) {
            command.requirementUids().forEach(uid -> WorkflowTelemetryValidation.rejectReservedMarkers(uid));
        }
        WorkflowTelemetryValidation.validateEconomics(
                command.modelInvocationCount(), command.wallClockMinutes(), command.costProxy(), command.tokenUsage());

        var existing = runRepository.findRunForUpdate(
                command.project(), command.repo(), command.issueNumber(), command.branch());
        WorkflowRun saved;
        if (existing.isPresent()) {
            var run = existing.get();
            WorkflowTelemetryValidation.validateChronology(run, command);
            WorkflowRunCommandMapper.applyRunCommand(run, command);
            saved = runRepository.save(run);
        } else {
            WorkflowTelemetryValidation.validateChronology(null, command);
            var run = new WorkflowRun(command.project(), command.workflowType(), command.provenance());
            WorkflowRunCommandMapper.applyRunCommand(run, command);
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
        supersedeAbandonedAttempts(command, saved);
        log.info(
                "workflow_run_recorded: project={} issue={} branch={} state={} provenance={}",
                saved.getProject(),
                saved.getIssueNumber(),
                saved.getBranch(),
                saved.getFinalState(),
                saved.getProvenance());
        publishChange(WorkflowTelemetryChangeEvent.run(saved.getProject(), saved.getId()));
        return loadRequirementUids(saved);
    }

    /**
     * Append one phase/gate event to an existing run, idempotently per logical fact (issue #1435).
     *
     * <p>Two fields are derived when the emitter cannot attest them:
     *
     * <ul>
     *   <li>{@code cycleIndex} — a {@code STARTED} event opens an attempt, so it takes the next
     *       ordinal for {@code (run, phase, STARTED)} read from durable history. Every other event
     *       type takes attempt {@code 0}: an emitter that cannot order attempts is describing the
     *       first one, and that is what lets an issue-thread backfill land on the live attempt
     *       instead of appending a phantom retry.
     *   <li>{@code sourceId} — {@code phase:eventType:cycleIndex}, the identity of the logical fact.
     * </ul>
     *
     * <p>A {@code (runId, sourceId)} that already exists returns the stored event untouched. The
     * table stays append-only per logical fact rather than per HTTP call, so a retry, a duplicated
     * boundary, and reconciliation all converge instead of inflating per-phase counts.
     */
    @Transactional
    public WorkflowPhaseEvent recordPhaseEvent(RecordPhaseEventCommand command) {
        if (command.runId() == null) {
            throw new DomainValidationException("runId must not be null");
        }
        WorkflowTelemetryValidation.requireText(command.project(), PROJECT_FIELD);
        WorkflowTelemetryValidation.requireText(command.phase(), "phase");
        if (command.eventType() == null) {
            throw new DomainValidationException("eventType must not be null");
        }
        if (command.occurredAt() == null) {
            throw new DomainValidationException("occurredAt must not be null");
        }
        if (command.provenance() == null) {
            throw new DomainValidationException("provenance must not be null");
        }
        WorkflowTelemetryValidation.rejectReservedMarkers(command.phase(), command.outcome(), command.sourceId());
        if (command.durationMs() != null && command.durationMs() < 0) {
            throw new DomainValidationException("durationMs must not be negative");
        }
        if (command.cycleIndex() != null && command.cycleIndex() < 0) {
            throw new DomainValidationException("cycleIndex must not be negative");
        }
        // The project-scoped lookup is what authorizes the write; afterwards command.runId() is the
        // proven identity of that run, so the derivation and idempotency lookups key on it directly.
        // It takes the row lock so the ordinal derivation, the existence check, and the insert below
        // are one atomic sequence per run rather than a race between concurrent deliveries.
        var run = runRepository
                .findByIdAndProjectForUpdate(command.runId(), command.project())
                .orElseThrow(() -> new NotFoundException(RUN_NOT_FOUND + command.runId()));

        int cycleIndex = resolveCycleIndex(command, command.runId());
        String sourceId = command.sourceId() != null && !command.sourceId().isBlank()
                ? command.sourceId()
                : WorkflowPhaseEvent.deriveSourceId(command.phase(), command.eventType(), cycleIndex);

        var alreadyRecorded = phaseEventRepository.findByRunIdAndSourceId(command.runId(), sourceId);
        if (alreadyRecorded.isPresent()) {
            // The attempt is already recorded, so its batch is too. Re-appending the findings here
            // would double every count on an at-least-once redelivery, which is precisely what the
            // shared identity exists to prevent.
            return alreadyRecorded.get();
        }

        var event = new WorkflowPhaseEvent(
                command.runId(),
                run.getProject(),
                command.phase(),
                command.eventType(),
                command.occurredAt(),
                command.durationMs(),
                command.provenance());
        event.setCycleIndex(cycleIndex);
        event.setOutcome(command.outcome());
        event.setSourceId(sourceId);
        event.setStationId(command.stationId());
        event.setStationResult(command.stationResult());
        var appended = phaseEventRepository.save(event);
        // Same transaction as the attempt: a partial batch must never be readable as a complete
        // gate outcome, so either the attempt and every finding it observed commit together or
        // neither does.
        measurementService.persistFindings(appended, command.findings());
        // Only a genuinely new append is announced. The idempotent (runId, sourceId) hit above
        // returns early without publishing, so a retry or a backfill of an already-delivered fact
        // does not re-notify; a subscriber that missed the original resynchronizes on reconnect.
        publishChange(
                WorkflowTelemetryChangeEvent.phaseEvent(appended.getProject(), appended.getRunId(), appended.getId()));
        return appended;
    }

    private int resolveCycleIndex(RecordPhaseEventCommand command, UUID runId) {
        if (command.cycleIndex() != null) {
            return command.cycleIndex();
        }
        if (command.eventType() != PhaseEventType.STARTED) {
            return 0;
        }
        long priorAttempts =
                phaseEventRepository.countByRunIdAndPhaseAndEventType(runId, command.phase(), PhaseEventType.STARTED);
        return Math.toIntExact(priorAttempts);
    }

    /** Import manual economics for an existing run; only non-null fields are applied. */
    @Transactional
    public WorkflowRun importCost(ImportRunCostCommand command) {
        if (command.runId() == null) {
            throw new DomainValidationException("runId must not be null");
        }
        WorkflowTelemetryValidation.requireText(command.project(), PROJECT_FIELD);
        WorkflowTelemetryValidation.rejectReservedMarkers(command.provider(), command.model(), command.costCurrency());
        WorkflowTelemetryValidation.validateEconomics(
                command.modelInvocationCount(), command.wallClockMinutes(), command.costProxy(), command.tokenUsage());
        var run = runRepository
                .findByIdAndProject(command.runId(), command.project())
                .orElseThrow(() -> new NotFoundException(RUN_NOT_FOUND + command.runId()));
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
        var saved = runRepository.save(run);
        publishChange(WorkflowTelemetryChangeEvent.run(saved.getProject(), saved.getId()));
        return loadRequirementUids(saved);
    }

    /**
     * Phase events for one run, oldest first (bounded). The run is resolved project-scoped first, so
     * a caller holding a run id from another project gets not-found rather than that project's
     * events, and the event query is itself scoped on the event's denormalized project column.
     */
    @Transactional(readOnly = true)
    public List<WorkflowPhaseEvent> listPhaseEvents(UUID runId, String project, int limit) {
        if (runId == null) {
            throw new DomainValidationException("runId must not be null");
        }
        WorkflowTelemetryValidation.requireText(project, PROJECT_FIELD);
        // Resolving the run project-scoped is the authorization step; the entity itself is not needed
        // because the event query is scoped on its own denormalized project column.
        var authorized = runRepository
                .findByIdAndProject(runId, project)
                .orElseThrow(() -> new NotFoundException(RUN_NOT_FOUND + runId));
        log.trace("workflow_run_phase_events_requested: run={} project={}", authorized.getId(), project);
        int bounded = Math.clamp(limit, 1, MAX_PHASE_EVENT_LIST_SIZE);
        return phaseEventRepository.findByRunIdAndProjectOrderByOccurredAtAscIdAsc(
                runId, project, PageRequest.of(0, bounded));
    }

    /**
     * One run, resolved project-scoped. A caller holding a run id from another project gets
     * not-found rather than that run, so a run UUID is never on its own an authorization capability.
     */
    @Transactional(readOnly = true)
    public WorkflowRun getRun(UUID runId, String project) {
        if (runId == null) {
            throw new DomainValidationException("runId must not be null");
        }
        WorkflowTelemetryValidation.requireText(project, PROJECT_FIELD);
        return loadRequirementUids(runRepository
                .findByIdAndProject(runId, project)
                .orElseThrow(() -> new NotFoundException(RUN_NOT_FOUND + runId)));
    }

    /** One phase event, resolved against the event's own denormalized project column. */
    @Transactional(readOnly = true)
    public WorkflowPhaseEvent getPhaseEvent(UUID eventId, String project) {
        if (eventId == null) {
            throw new DomainValidationException("eventId must not be null");
        }
        WorkflowTelemetryValidation.requireText(project, PROJECT_FIELD);
        return phaseEventRepository
                .findByIdAndProject(eventId, project)
                .orElseThrow(() -> new NotFoundException(PHASE_EVENT_NOT_FOUND + eventId));
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
        WorkflowTelemetryValidation.validateWindow(filter.from(), filter.to());
        WorkflowTelemetryValidation.rejectReservedMarkers(
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

    /**
     * A fresh live attempt on a different branch of the same work item retires the previous attempt
     * (issue #1435). This is the only abandonment the tool layer can observe: an agent that stops
     * working never emits anything, so without this the earlier run would stay {@code RUNNING}
     * forever. Scoped to live opens so replaying an old issue thread through
     * {@code gc_workflow_run_ingest} can never close a run that is genuinely in flight.
     */
    private void supersedeAbandonedAttempts(RecordWorkflowRunCommand command, WorkflowRun saved) {
        boolean liveOpen = command.provenance() == TelemetryProvenance.LIVE_EMISSION
                && command.finalState() == WorkflowRunState.RUNNING;
        if (!liveOpen || command.issueNumber() == null) {
            return;
        }
        var abandoned = runRepository.findOpenRunsForWorkItem(
                command.project(), command.repo(), command.issueNumber(), command.branch());
        for (var run : abandoned) {
            run.setFinalState(WorkflowRunState.SUPERSEDED);
            if (run.getEndedAt() == null) {
                // Dated from the successor's start, which is when this attempt demonstrably stopped
                // being worked on. That is a real observation, unlike the wall-clock moment at which
                // this sweep happens to run.
                run.setEndedAt(saved.getStartedAt() != null ? saved.getStartedAt() : Instant.now());
            }
            runRepository.save(run);
            log.info(
                    "workflow_run_superseded: project={} issue={} branch={} superseded_by_branch={}",
                    run.getProject(),
                    run.getIssueNumber(),
                    run.getBranch(),
                    saved.getBranch());
            // A retired attempt is a state change a watching dashboard must see, not a silent
            // bookkeeping write: without this the superseded run would sit at RUNNING on screen
            // until the next poll (issue #1436).
            publishChange(WorkflowTelemetryChangeEvent.run(run.getProject(), run.getId()));
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

    /**
     * Announce a committed telemetry change. Spring holds the notification until the surrounding
     * transaction commits, so a rolled-back write is never announced; see the {@code AFTER_COMMIT}
     * listener that delivers it (issue #1436).
     */
    private void publishChange(WorkflowTelemetryChangeEvent change) {
        eventPublisher.publishEvent(change);
    }
}
