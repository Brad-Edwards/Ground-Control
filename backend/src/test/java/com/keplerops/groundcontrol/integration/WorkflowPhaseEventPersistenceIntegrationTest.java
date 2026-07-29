package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RunAggregate;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowRunFilter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Phase-event persistence against real Postgres: the {@code (run_id, source_id)} unique index and the
 * durable ADR-036 step observation (issue #1354). Split from
 * {@code WorkflowTelemetryAggregationIntegrationTest} for the 500-LOC limit; the aggregation file
 * keeps the rollup/hot-spot coverage.
 */
class WorkflowPhaseEventPersistenceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowPhaseEventRepository phaseEventRepository;

    @Autowired
    private WorkflowTelemetryService service;

    @Autowired
    private ProjectRepository projectRepository;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");
    private static final Instant START = Instant.parse("2026-06-01T10:00:00Z");

    @BeforeEach
    void clear() {
        phaseEventRepository.deleteAll();
        runRepository.deleteAll();
    }

    @Test
    void theUniqueSourceIndexRejectsADuplicateAtTheDatabaseLevel() {
        // The service check-then-insert is serialized on the run row, but the index is the backstop
        // that holds if any future writer bypasses the service.
        var run = saveRun("gc", 3, START, null, WorkflowRunState.RUNNING, WorkflowRunOutcome.NONE, null);
        runRepository.flush();

        phaseEventRepository.saveAndFlush(rawEvent(run.getId()));
        var duplicate = rawEvent(run.getId());
        assertThatThrownBy(() -> phaseEventRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stepObservationsAreExcludedFromPhaseHotspotsButVisibleOnTheRunEventSurface() {
        // Issue #1354: a durable ADR-036 step observation and a lifecycle station attempt can share a
        // stage, but they are different facts. The hot-spot rollup must count only the station attempt,
        // while the run-scoped event surface returns both — it is the queryable per-step record.
        var run = saveRun(
                "gc", 1, START, START.plusSeconds(600), WorkflowRunState.MERGED, WorkflowRunOutcome.MERGED, "10.0000");
        // A real lifecycle station attempt at the completion gate (emitter defaults to ADR-061).
        phaseEventRepository.save(event(run.getId(), "completion_gate", PhaseEventType.COMPLETED, 1, 1000L));
        // A routed-step cost observation for the same stage: same phase string, ADR-036 emitter.
        service.recordPhaseEvent(stepObservation(run.getId(), "completion_gate"));

        RunAggregate gc = service.aggregate(new WorkflowRunFilter(FROM, TO, "gc", null, null, null, null, null));
        var hotspot = gc.phaseHotspots().stream()
                .filter(h -> "completion_gate".equals(h.phase()))
                .findFirst()
                .orElseThrow();
        // Only the station attempt is counted; the step observation would otherwise double the count
        // and skew the duration percentiles with a parallel per-step stream.
        assertThat(hotspot.eventCount()).isEqualTo(1);

        var events = service.listPhaseEvents(run.getId(), "gc", 50);
        assertThat(events).hasSize(2);
        var stepRow = events.stream()
                .filter(e -> e.getEmitter() == PhaseEventEmitter.ADR036_STEP_JSONL)
                .findFirst()
                .orElseThrow();
        // The backend resolved the station from the stage, kept the row UNOBSERVED, and carried tier.
        assertThat(stepRow.getStationId()).isEqualTo("completion_gate");
        assertThat(stepRow.getStationResult()).isEqualTo(StationResult.UNOBSERVED);
        assertThat(stepRow.getTier()).isEqualTo(CapabilityTier.LOW);
        assertThat(stepRow.getSourceId()).isEqualTo("adr036_step:completion_gate:0");
    }

    @Test
    void aStepObservationReplayWithDifferentFactsIsAConflict() {
        // Issue #1354: a step observation's namespaced identity is stable and its measurement facts are
        // immutable. A retry with the same facts converges to one row; a reuse with different facts is a
        // conflict, not a silent overwrite of a first-hand measurement.
        var run = saveRun("gc", 1, START, null, WorkflowRunState.RUNNING, WorkflowRunOutcome.NONE, null);
        service.recordPhaseEvent(stepObservation(run.getId(), "completion_gate"));

        // Same identity, same facts → idempotent no-op, still one row.
        service.recordPhaseEvent(stepObservation(run.getId(), "completion_gate"));
        assertThat(phaseEventRepository.count()).isEqualTo(1);

        // Same identity (run + stage + attempt 0 derive the same source id), a different model.
        var conflicting = stepObservationWithModel(run.getId(), "completion_gate", "claude-opus-4-8");
        assertThatThrownBy(() -> service.recordPhaseEvent(conflicting)).isInstanceOf(ConflictException.class);
        assertThat(phaseEventRepository.count()).isEqualTo(1);
    }

    @Test
    void findForGraphProjectionExcludesStepObservations() {
        // Issue #1354: the context-graph projection is the second consumer that must own the ADR-061
        // emitter — the twin of the hot-spot exclusion. Without this a future refactor could drop the
        // graph predicate and a routed-step cost fact would silently become a workflow-graph node.
        var project = projectRepository
                .findByIdentifier("gc")
                .orElseGet(() -> projectRepository.save(new Project("gc", "GC")));
        var run = saveRun("gc", 1, START, null, WorkflowRunState.RUNNING, WorkflowRunOutcome.NONE, null);
        var lifecycle =
                phaseEventRepository.save(event(run.getId(), "completion_gate", PhaseEventType.COMPLETED, 1, 1000L));
        service.recordPhaseEvent(stepObservation(run.getId(), "completion_gate"));

        var projected = phaseEventRepository.findForGraphProjection(project.getId());

        // Only the ADR-061 lifecycle attempt reaches the graph; the ADR-036 step observation is excluded.
        assertThat(projected).extracting(WorkflowPhaseEvent::getId).containsExactly(lifecycle.getId());
        assertThat(projected).allMatch(e -> e.getEmitter() == PhaseEventEmitter.ADR061_WORKFLOW_TELEMETRY);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private WorkflowRun saveRun(
            String project,
            int issue,
            Instant started,
            Instant ended,
            WorkflowRunState state,
            WorkflowRunOutcome outcome,
            String cost) {
        var run = new WorkflowRun(project, "implement", TelemetryProvenance.ISSUE_THREAD);
        run.setIssueNumber(issue);
        run.setRepo("owner/repo");
        run.setBranch(issue + "-branch");
        run.setStartedAt(started);
        run.setEndedAt(ended);
        run.setFinalState(state);
        run.setOutcome(outcome);
        if (cost != null) {
            run.setCostProxy(new BigDecimal(cost));
        }
        return runRepository.save(run);
    }

    private static WorkflowPhaseEvent event(UUID runId, String phase, PhaseEventType type, Integer cycle, Long ms) {
        var event = new WorkflowPhaseEvent(runId, "gc", phase, type, START, ms, TelemetryProvenance.ISSUE_THREAD);
        event.setCycleIndex(cycle);
        event.setOutcome("x");
        return event;
    }

    /** An event built straight from the entity, bypassing the service, to exercise the index itself. */
    private static WorkflowPhaseEvent rawEvent(UUID runId) {
        var event = new WorkflowPhaseEvent(
                runId, "gc", "ci", PhaseEventType.COMPLETED, START, 10L, TelemetryProvenance.LIVE_EMISSION);
        event.setCycleIndex(0);
        return event;
    }

    /** A durable ADR-036 step observation (issue #1354): the ADR-036 emitter, no station id, no verdict. */
    private static RecordPhaseEventCommand stepObservation(UUID runId, String stage) {
        return stepObservationWithModel(runId, stage, "claude-haiku-4-5");
    }

    private static RecordPhaseEventCommand stepObservationWithModel(UUID runId, String stage, String model) {
        return new RecordPhaseEventCommand(
                runId,
                "gc",
                stage,
                PhaseEventType.COMPLETED,
                0,
                START,
                1200L,
                "ok",
                TelemetryProvenance.LIVE_EMISSION,
                "adr036_step:" + stage + ":0",
                null,
                null,
                null,
                null,
                PhaseEventEmitter.ADR036_STEP_JSONL,
                "gc.measurement/v1",
                "Step 6",
                CapabilityTier.LOW,
                model,
                model,
                Boolean.TRUE,
                8421L,
                612L);
    }
}
