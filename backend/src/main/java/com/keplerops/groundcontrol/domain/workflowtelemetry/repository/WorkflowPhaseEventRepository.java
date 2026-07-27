package com.keplerops.groundcontrol.domain.workflowtelemetry.repository;

import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
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
     * Project-scoped, chronologically ordered events for one run. {@code project} is matched on the
     * event's own denormalized column so a caller cannot page another project's events even with a
     * valid run id.
     */
    List<WorkflowPhaseEvent> findByRunIdAndProjectOrderByOccurredAtAscIdAsc(
            UUID runId, String project, Pageable pageable);

    /** Project-scoped read for the mixed graph, resolving its UUID to the immutable identifier. */
    @Query("SELECT e FROM WorkflowPhaseEvent e "
            + "WHERE e.project = (SELECT p.identifier FROM Project p WHERE p.id = :projectId) "
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
