package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.ImportRunCostCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordWorkflowRunCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RunAggregate;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowRunFilter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Verifies the database-side aggregation in {@code WorkflowRunRepository.aggregateRuns} and
 * {@code WorkflowPhaseEventRepository.aggregatePhaseHotspots} against real Postgres: the {@code COUNT
 * FILTER}, {@code percentile_disc}, cost sums, {@code COALESCE(started_at, created_at)} window
 * anchor, the requirement-UID EXISTS filter, and project scoping. The unit test mocks the
 * repositories, so this is the only coverage of the native queries.
 */
class WorkflowTelemetryAggregationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowPhaseEventRepository phaseEventRepository;

    @Autowired
    private WorkflowTelemetryService service;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");
    private static final Instant START = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant BEFORE_WINDOW = Instant.parse("2026-05-01T10:00:00Z");

    @BeforeEach
    void clear() {
        phaseEventRepository.deleteAll();
        runRepository.deleteAll();
    }

    @Test
    void aggregatesThroughputOutcomesCycleTimeAndCostPerOutcomeInTheDatabase() {
        // 3 merged runs in "gc" with cycle times 10/20/30 min and costs 30/30/60.
        saveRun("gc", 1, START, START.plusSeconds(600), WorkflowRunState.MERGED, WorkflowRunOutcome.MERGED, "30.0000");
        saveRun("gc", 2, START, START.plusSeconds(1200), WorkflowRunState.MERGED, WorkflowRunOutcome.MERGED, "30.0000");
        saveRun("gc", 3, START, START.plusSeconds(1800), WorkflowRunState.MERGED, WorkflowRunOutcome.MERGED, "60.0000");
        // 1 closed-without-merge (40 min cycle time) and 1 still-running run (no cycle time) in "gc".
        saveRun(
                "gc",
                4,
                START,
                START.plusSeconds(2400),
                WorkflowRunState.CLOSED,
                WorkflowRunOutcome.CLOSED_WITHOUT_MERGE,
                "15.0000");
        saveRun("gc", 5, START, null, WorkflowRunState.RUNNING, WorkflowRunOutcome.NONE, null);
        // A run in another project must NOT leak into the gc-scoped aggregate.
        saveRun(
                "other",
                9,
                START,
                START.plusSeconds(600),
                WorkflowRunState.MERGED,
                WorkflowRunOutcome.MERGED,
                "999.0000");

        RunAggregate gc = service.aggregate(new WorkflowRunFilter(FROM, TO, "gc", null, null, null, null, null));

        assertThat(gc.totalRuns()).isEqualTo(5);
        assertThat(gc.mergedRuns()).isEqualTo(3);
        assertThat(gc.closedRuns()).isEqualTo(1);
        assertThat(gc.activeRuns()).isEqualTo(1);
        // Cycle time spans all COMPLETED runs (merged 10/20/30 + closed 40); the running run has none.
        // percentile_disc over {10,20,30,40} minutes (nearest-rank): p50 -> 20, p95 -> 40.
        assertThat(gc.cycleTimeP50Min()).isEqualTo(20.0);
        assertThat(gc.cycleTimeP95Min()).isEqualTo(40.0);
        // merged cost 30+30+60 = 120 over 3 merged runs -> 40 per merged run; cross-project run excluded.
        assertThat(gc.mergedCostProxy()).isEqualByComparingTo("120.0000");
        assertThat(gc.costProxyPerMergedRun()).isEqualByComparingTo("40.0000");
        assertThat(gc.totalCostProxy()).isEqualByComparingTo("135.0000");
    }

    @Test
    void crossProjectAggregateWithNullProjectSpansEveryProject() {
        saveRun("gc", 1, START, START.plusSeconds(600), WorkflowRunState.MERGED, WorkflowRunOutcome.MERGED, "10.0000");
        saveRun(
                "other",
                2,
                START,
                START.plusSeconds(600),
                WorkflowRunState.MERGED,
                WorkflowRunOutcome.MERGED,
                "20.0000");

        RunAggregate all = service.aggregate(new WorkflowRunFilter(FROM, TO, null, null, null, null, null, null));

        assertThat(all.totalRuns()).isEqualTo(2);
        assertThat(all.totalCostProxy()).isEqualByComparingTo("30.0000");
    }

    @Test
    void windowExcludesRunsOutsideTheRange() {
        saveRun(
                "gc",
                1,
                BEFORE_WINDOW,
                BEFORE_WINDOW.plusSeconds(600),
                WorkflowRunState.MERGED,
                WorkflowRunOutcome.MERGED,
                "10.0000");

        RunAggregate gc = service.aggregate(new WorkflowRunFilter(FROM, TO, "gc", null, null, null, null, null));

        assertThat(gc.totalRuns()).isZero();
    }

    @Test
    void requirementFilterMatchesRunsLinkedToThatUidViaChildTable() {
        var withReq = saveRun(
                "gc", 1, START, START.plusSeconds(600), WorkflowRunState.MERGED, WorkflowRunOutcome.MERGED, "10.0000");
        withReq.setRequirementUids(Set.of("GC-O009"));
        runRepository.save(withReq);
        saveRun("gc", 2, START, START.plusSeconds(600), WorkflowRunState.MERGED, WorkflowRunOutcome.MERGED, "20.0000");

        RunAggregate matching =
                service.aggregate(new WorkflowRunFilter(FROM, TO, "gc", null, null, null, null, "GC-O009"));
        RunAggregate nonMatching =
                service.aggregate(new WorkflowRunFilter(FROM, TO, "gc", null, null, null, null, "GC-O999"));

        assertThat(matching.totalRuns()).isEqualTo(1);
        assertThat(nonMatching.totalRuns()).isZero();
    }

    @Test
    void phaseHotspotsAggregatePerPhaseWithFailedAndCycleCounts() {
        var run = saveRun(
                "gc", 1, START, START.plusSeconds(1800), WorkflowRunState.MERGED, WorkflowRunOutcome.MERGED, "30.0000");
        // ci phase: two cycles, one failed, durations 1000/2000.
        phaseEventRepository.save(event(run.getId(), "ci", PhaseEventType.FAILED, 1, 1000L));
        phaseEventRepository.save(event(run.getId(), "ci", PhaseEventType.COMPLETED, 2, 2000L));
        // codex_review phase: one completed cycle.
        phaseEventRepository.save(event(run.getId(), "codex_review", PhaseEventType.COMPLETED, 1, 500L));

        RunAggregate gc = service.aggregate(new WorkflowRunFilter(FROM, TO, "gc", null, null, null, null, null));

        var ci = gc.phaseHotspots().stream()
                .filter(h -> "ci".equals(h.phase()))
                .findFirst()
                .orElseThrow();
        assertThat(ci.eventCount()).isEqualTo(2);
        assertThat(ci.failedCount()).isEqualTo(1);
        assertThat(ci.maxCycleIndex()).isEqualTo(2);
        assertThat(ci.p50Ms()).isEqualTo(1000L);
    }

    @Test
    void phaseHotspotsRespectTheSameRunFiltersAsTheRollup() {
        // Two runs in the same project/window but different outcomes, each with a ci phase event.
        var merged = saveRun(
                "gc", 1, START, START.plusSeconds(600), WorkflowRunState.MERGED, WorkflowRunOutcome.MERGED, "10.0000");
        var closed = saveRun(
                "gc",
                2,
                START,
                START.plusSeconds(600),
                WorkflowRunState.CLOSED,
                WorkflowRunOutcome.CLOSED_WITHOUT_MERGE,
                "10.0000");
        phaseEventRepository.save(event(merged.getId(), "ci", PhaseEventType.COMPLETED, 1, 1000L));
        phaseEventRepository.save(event(closed.getId(), "ci", PhaseEventType.FAILED, 1, 2000L));

        // Filtering the aggregate by outcome=MERGED must scope the hot-spots to the merged run only,
        // so the response is one coherent population (issue #859 review finding).
        RunAggregate mergedOnly = service.aggregate(
                new WorkflowRunFilter(FROM, TO, "gc", null, null, null, WorkflowRunOutcome.MERGED, null));

        assertThat(mergedOnly.totalRuns()).isEqualTo(1);
        var ci = mergedOnly.phaseHotspots().stream()
                .filter(h -> "ci".equals(h.phase()))
                .findFirst()
                .orElseThrow();
        assertThat(ci.eventCount()).isEqualTo(1); // the closed run's failed ci event is excluded
        assertThat(ci.failedCount()).isZero();
    }

    @Test
    void recordRunUpsertMergesTheSameKeyIntoOneRow() {
        // First observation: run starts.
        service.recordRun(new RecordWorkflowRunCommand(
                "gc",
                "owner/repo",
                859,
                null,
                "859-feature",
                "implement",
                "claude-code",
                Set.of("GC-O009"),
                START,
                null,
                WorkflowRunState.RUNNING,
                WorkflowRunOutcome.NONE,
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        // Second observation: same (project, repo, issue, branch) reaches merge.
        service.recordRun(new RecordWorkflowRunCommand(
                "gc",
                "owner/repo",
                859,
                42,
                "859-feature",
                "implement",
                null,
                null,
                null,
                START.plusSeconds(1800),
                WorkflowRunState.MERGED,
                WorkflowRunOutcome.MERGED,
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null,
                new BigDecimal("25.0000"),
                "USD",
                null));

        assertThat(runRepository.count()).isEqualTo(1);
        var run = runRepository
                .findRunForUpsert("gc", "owner/repo", 859, "859-feature")
                .orElseThrow();
        assertThat(run.getFinalState()).isEqualTo(WorkflowRunState.MERGED);
        assertThat(run.getPrNumber()).isEqualTo(42);
        assertThat(run.getStartedAt()).isEqualTo(START); // earlier start preserved
        assertThat(run.getCostProxy()).isEqualByComparingTo("25.0000");
    }

    @Test
    void runIdWritePathsAreProjectScoped() {
        var run = saveRun("gc", 1, START, null, WorkflowRunState.RUNNING, WorkflowRunOutcome.NONE, null);

        // A caller scoped to another project cannot append a phase event or import cost for this run:
        // the project-scoped lookup resolves to empty, so it is rejected exactly like a missing run.
        var foreignEvent = new RecordPhaseEventCommand(
                run.getId(),
                "other",
                "ci",
                PhaseEventType.COMPLETED,
                1,
                START,
                100L,
                "x",
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> service.recordPhaseEvent(foreignEvent)).isInstanceOf(NotFoundException.class);
        var foreignCost = new ImportRunCostCommand(
                run.getId(), "other", null, null, null, null, new BigDecimal("5.0000"), "USD", null);
        assertThatThrownBy(() -> service.importCost(foreignCost)).isInstanceOf(NotFoundException.class);
        assertThat(phaseEventRepository.count()).isZero();

        // The owning project succeeds.
        service.recordPhaseEvent(new RecordPhaseEventCommand(
                run.getId(),
                "gc",
                "ci",
                PhaseEventType.COMPLETED,
                1,
                START,
                100L,
                "x",
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null));
        service.importCost(new ImportRunCostCommand(
                run.getId(), "gc", null, null, null, null, new BigDecimal("5.0000"), "USD", null));
        assertThat(phaseEventRepository.count()).isEqualTo(1);
        assertThat(runRepository
                        .findByIdAndProject(run.getId(), "gc")
                        .orElseThrow()
                        .getCostProxy())
                .isEqualByComparingTo("5.0000");
    }

    @Test
    void uniqueUpsertKeyTreatsNullColumnsAsEqual() {
        // NULLS NOT DISTINCT: two runs with the same project and all-null repo/issue_number/branch
        // collide on idx_workflow_run_upsert_key, so the database rejects the duplicate. This is the
        // backstop the service-level upsert relies on for partial run identities.
        runRepository.saveAndFlush(new WorkflowRun("gc", "implement", TelemetryProvenance.ISSUE_THREAD));
        var duplicate = new WorkflowRun("gc", "implement", TelemetryProvenance.ISSUE_THREAD);
        assertThatThrownBy(() -> runRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- V204 phase-event dedup (issue #1435) --------------------------------------------------

    @Test
    void theSameLogicalPhaseFactRecordedTwiceIsStoredOnce() {
        // The convergence this PR exists to deliver: live emission records a completed CI attempt,
        // then issue-thread reconciliation describes the same attempt. Both must resolve to one row,
        // or every per-phase count and first-pass-yield denominator double-counts it.
        var run = saveRun("gc", 1, START, null, WorkflowRunState.RUNNING, WorkflowRunOutcome.NONE, null);

        var live = new RecordPhaseEventCommand(
                run.getId(),
                "gc",
                "ci",
                PhaseEventType.COMPLETED,
                0,
                START,
                1000L,
                "green",
                TelemetryProvenance.LIVE_EMISSION,
                null,
                null,
                null,
                null);
        var backfill = new RecordPhaseEventCommand(
                run.getId(),
                "gc",
                "ci",
                PhaseEventType.COMPLETED,
                // The reconciliation path cannot attest attempt order, so it omits the ordinal.
                null,
                START.plusSeconds(3600),
                null,
                null,
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null);

        var first = service.recordPhaseEvent(live);
        var second = service.recordPhaseEvent(backfill);

        assertThat(phaseEventRepository.count()).isEqualTo(1);
        assertThat(second.getId()).isEqualTo(first.getId());
        // The original observation wins: reconciliation refines coverage, it does not overwrite a
        // first-hand measurement with a reconstructed one.
        assertThat(second.getProvenance()).isEqualTo(TelemetryProvenance.LIVE_EMISSION);
        assertThat(second.getDurationMs()).isEqualTo(1000L);
    }

    @Test
    void aSecondAttemptAtTheSamePhaseIsStoredSeparately() {
        // The flip side: dedup must not swallow a genuine retry, or iterations-to-green collapses
        // to 1 for every station.
        var run = saveRun("gc", 2, START, null, WorkflowRunState.RUNNING, WorkflowRunOutcome.NONE, null);

        service.recordPhaseEvent(phaseCommand(run.getId(), PhaseEventType.STARTED, null));
        service.recordPhaseEvent(phaseCommand(run.getId(), PhaseEventType.STARTED, null));

        assertThat(phaseEventRepository.count()).isEqualTo(2);
        assertThat(phaseEventRepository.findAll().stream()
                        .map(WorkflowPhaseEvent::getCycleIndex)
                        .sorted()
                        .toList())
                .containsExactly(0, 1);
    }

    @Test
    void theUniqueSourceIndexRejectsADuplicateAtTheDatabaseLevel() {
        // The service check-then-insert is serialized on the run row, but the index is the backstop
        // that holds if any future writer bypasses the service. Mirrors
        // uniqueUpsertKeyTreatsNullColumnsAsEqual for workflow_run.
        var run = saveRun("gc", 3, START, null, WorkflowRunState.RUNNING, WorkflowRunOutcome.NONE, null);
        runRepository.flush();

        phaseEventRepository.saveAndFlush(rawEvent(run.getId()));
        var duplicate = rawEvent(run.getId());
        assertThatThrownBy(() -> phaseEventRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static RecordPhaseEventCommand phaseCommand(java.util.UUID runId, PhaseEventType type, Integer cycleIndex) {
        return new RecordPhaseEventCommand(
                runId,
                "gc",
                "ci",
                type,
                cycleIndex,
                START,
                5L,
                null,
                TelemetryProvenance.LIVE_EMISSION,
                null,
                null,
                null,
                null);
    }

    /** An event built straight from the entity, bypassing the service, to exercise the index itself. */
    private static WorkflowPhaseEvent rawEvent(java.util.UUID runId) {
        var event = new WorkflowPhaseEvent(
                runId, "gc", "ci", PhaseEventType.COMPLETED, START, 10L, TelemetryProvenance.LIVE_EMISSION);
        event.setCycleIndex(0);
        return event;
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
}
