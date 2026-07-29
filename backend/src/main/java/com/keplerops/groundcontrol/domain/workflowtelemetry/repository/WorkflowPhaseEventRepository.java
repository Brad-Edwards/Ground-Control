package com.keplerops.groundcontrol.domain.workflowtelemetry.repository;

import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowPhaseEventRepository extends JpaRepository<WorkflowPhaseEvent, UUID> {

    /**
     * Attempt ordinal source for a {@code STARTED} event whose emitter cannot attest attempt order
     * (issue #1435). Counting durable history rather than in-process state keeps the ordinal correct
     * across an emitter restart, which is precisely when an in-memory counter would silently reset.
     */
    long countByRunIdAndPhaseAndEventType(UUID runId, String phase, PhaseEventType eventType);

    /** Idempotency lookup: the logical fact already recorded for this run under {@code sourceId}. */
    Optional<WorkflowPhaseEvent> findByRunIdAndSourceId(UUID runId, String sourceId);

    /**
     * Project-scoped single-event read. Matched on the event's own denormalized project column so an
     * event id from another project resolves to empty rather than to that project's event.
     */
    Optional<WorkflowPhaseEvent> findByIdAndProject(UUID id, String project);

    /**
     * Project-scoped, chronologically ordered events for one run. {@code project} is matched on the
     * event's own denormalized column so a caller cannot page another project's events even with a
     * valid run id.
     */
    List<WorkflowPhaseEvent> findByRunIdAndProjectOrderByOccurredAtAscIdAsc(
            UUID runId, String project, Pageable pageable);

    /**
     * Project-scoped read for the mixed graph, resolving its UUID to the immutable identifier.
     *
     * <p>Selects the ADR-061 lifecycle emitter positively (issue #1354): the graph projects
     * lifecycle/station attempts, so it must own its emitter rather than accept "anything that is not
     * an ADR-036 step observation" — a future phase-event emitter would otherwise silently become a
     * graph edge. Step observations are routed-step cost facts and stay out of the graph.
     */
    @Query("SELECT e FROM WorkflowPhaseEvent e "
            + "WHERE e.project = (SELECT p.identifier FROM Project p WHERE p.id = :projectId) "
            + "AND e.emitter = com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter.ADR061_WORKFLOW_TELEMETRY "
            + "ORDER BY e.occurredAt, e.id")
    List<WorkflowPhaseEvent> findForGraphProjection(@Param("projectId") UUID projectId);

    /**
     * Per-phase hot-spot rollup over {@code [from, to)} in the database: event count, failed-gate
     * count, escalation count, p50/p95 duration, and the max cycle index (which surfaces review/CI
     * cycle counts). Grouping and percentiles run in Postgres against the {@code (project, phase,
     * occurred_at)} index.
     *
     * <p>Joins {@code workflow_run} so the hot-spots reflect the SAME filtered run population as
     * {@code WorkflowRunRepository.aggregateRuns} (repo / runtime / workflowType / outcome /
     * requirement). Without the join a filtered dashboard would show run totals for one
     * repo/requirement/outcome while the hot-spot table mixed in events from every run in the window.
     */
    @Query(
            value = "SELECT e.phase AS \"phase\","
                    + " COUNT(*) AS \"eventCount\","
                    + " COUNT(*) FILTER (WHERE e.event_type = 'FAILED') AS \"failedCount\","
                    + " COUNT(*) FILTER (WHERE e.event_type = 'ESCALATED') AS \"escalatedCount\","
                    + " percentile_disc(0.5) WITHIN GROUP (ORDER BY e.duration_ms)"
                    + "   FILTER (WHERE e.duration_ms IS NOT NULL) AS \"p50Ms\","
                    + " percentile_disc(0.95) WITHIN GROUP (ORDER BY e.duration_ms)"
                    + "   FILTER (WHERE e.duration_ms IS NOT NULL) AS \"p95Ms\","
                    + " MAX(e.cycle_index) AS \"maxCycleIndex\""
                    + " FROM workflow_phase_event e"
                    + " JOIN workflow_run r ON r.id = e.run_id"
                    + " WHERE e.occurred_at >= :from AND e.occurred_at < :to"
                    // The hot-spot rollup owns the ADR-061 lifecycle emitter explicitly (issue #1354),
                    // rather than counting "anything that is not an ADR-036 step observation": a future
                    // phase-event emitter must not silently inflate a phase's event count and skew its
                    // duration percentiles. Step observations are a parallel per-step cost stream.
                    + "   AND e.emitter = 'ADR061_WORKFLOW_TELEMETRY'"
                    + "   AND (:project IS NULL OR e.project = :project)"
                    + "   AND (:repo IS NULL OR r.repo = :repo)"
                    + "   AND (:runtime IS NULL OR r.runtime_driver = :runtime)"
                    + "   AND (:workflowType IS NULL OR r.workflow_type = :workflowType)"
                    + "   AND (:outcome IS NULL OR r.outcome = :outcome)"
                    + "   AND (:requirement IS NULL OR EXISTS ("
                    + "        SELECT 1 FROM workflow_run_requirement_uid u"
                    + "        WHERE u.run_id = r.id AND u.requirement_uid = :requirement))"
                    + " GROUP BY e.phase"
                    + " ORDER BY e.phase",
            nativeQuery = true)
    List<PhaseHotspotRow> aggregatePhaseHotspots(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("project") String project,
            @Param("repo") String repo,
            @Param("runtime") String runtime,
            @Param("workflowType") String workflowType,
            @Param("outcome") String outcome,
            @Param("requirement") String requirement);

    /**
     * Evaluable station attempts over {@code [from, to)} for the ADR-090 yield formulas.
     *
     * <p>Only {@code PASS} and {@code FAIL} are returned: skipped, cancelled, not-evaluable and
     * unobserved attempts remain measurable coverage but must stay out of the yield and
     * iterations-to-green denominators, or an unmeasured gate reads as a failing one. This is also
     * what keeps ADR-036 step observations (issue #1354) out of the yield series without a second
     * predicate: a step observation is always {@code UNOBSERVED}, so it can never be evaluable.
     *
     * <p>The rows are the terminal event of each attempt. Ordering and de-duplication happen in
     * {@code StationYieldCalculator} rather than here, because "first pass wins" is a formula
     * decision that belongs somewhere it can be tested directly.
     */
    @Query(
            """
            select e.stationId, e.runId, e.cycleIndex, e.stationResult
              from WorkflowPhaseEvent e
             where e.project = :project
               and e.stationId is not null
               and e.stationResult in :evaluable
               and e.occurredAt >= :from
               and e.occurredAt < :to
            """)
    List<Object[]> findEvaluableAttempts(
            @Param("project") String project,
            @Param("evaluable") java.util.Collection<StationResult> evaluable,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** One already-aggregated row per phase. p50/p95 are null when no event in the phase recorded a duration. */
    interface PhaseHotspotRow {
        String getPhase();

        long getEventCount();

        long getFailedCount();

        long getEscalatedCount();

        Long getP50Ms();

        Long getP95Ms();

        Integer getMaxCycleIndex();
    }
}
