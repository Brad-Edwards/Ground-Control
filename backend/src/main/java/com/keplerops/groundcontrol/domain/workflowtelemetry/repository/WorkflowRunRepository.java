package com.keplerops.groundcontrol.domain.workflowtelemetry.repository;

import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    /**
     * Idempotency key lookup for ingestion: a run is uniquely identified by
     * {@code (project, repo, issueNumber, branch)}. Any of repo/issueNumber/branch may be null, so
     * each is matched null-safely rather than with {@code = null} (which never matches in SQL).
     *
     * <p>Unlocked, so it stays usable as an ordinary read. The write path takes
     * {@link #findRunForUpdate} instead.
     */
    @Query("SELECT r FROM WorkflowRun r WHERE r.project = :project "
            + "AND ((:repo IS NULL AND r.repo IS NULL) OR r.repo = :repo) "
            + "AND ((:issueNumber IS NULL AND r.issueNumber IS NULL) OR r.issueNumber = :issueNumber) "
            + "AND ((:branch IS NULL AND r.branch IS NULL) OR r.branch = :branch)")
    Optional<WorkflowRun> findRunForUpsert(
            @Param("project") String project,
            @Param("repo") String repo,
            @Param("issueNumber") Integer issueNumber,
            @Param("branch") String branch);

    /**
     * Same lookup as {@link #findRunForUpsert}, holding {@code PESSIMISTIC_WRITE} on the matched row
     * (issue #1435). Live emission writes to one run from several phase boundaries and the merge is
     * read-modify-write: without the row lock two concurrent observations both read the pre-merge row
     * and the second {@code save} silently drops the first one's fields. The unique index protects
     * only the insert race, not this one.
     *
     * <p>Separate from the unlocked variant because a pessimistic lock requires an active
     * transaction; forcing every reader of the natural key into one would be a wider contract change
     * than the write path needs.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM WorkflowRun r WHERE r.project = :project "
            + "AND ((:repo IS NULL AND r.repo IS NULL) OR r.repo = :repo) "
            + "AND ((:issueNumber IS NULL AND r.issueNumber IS NULL) OR r.issueNumber = :issueNumber) "
            + "AND ((:branch IS NULL AND r.branch IS NULL) OR r.branch = :branch)")
    Optional<WorkflowRun> findRunForUpdate(
            @Param("project") String project,
            @Param("repo") String repo,
            @Param("issueNumber") Integer issueNumber,
            @Param("branch") String branch);

    /** Recent runs for one project, newest first; for the active-status table and run list. */
    List<WorkflowRun> findByProjectOrderByCreatedAtDesc(String project, Pageable pageable);

    /**
     * Still-open runs for the same work item on a different branch (issue #1435). A new live attempt
     * on the same {@code (project, repo, issueNumber)} means the earlier attempt was abandoned; this
     * is the only abandonment the tool layer can actually observe, because an agent that walks away
     * never calls anything. {@code repo} and {@code branch} are matched null-safely, mirroring
     * {@link #findRunForUpsert}.
     */
    @Query("SELECT r FROM WorkflowRun r WHERE r.project = :project "
            + "AND ((:repo IS NULL AND r.repo IS NULL) OR r.repo = :repo) "
            + "AND r.issueNumber = :issueNumber "
            + "AND ((:branch IS NULL AND r.branch IS NOT NULL) OR r.branch <> :branch) "
            + "AND r.finalState IN (com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState.RUNNING, "
            + "com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState.READY_FOR_REVIEW, "
            + "com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState.ESCALATED)")
    List<WorkflowRun> findOpenRunsForWorkItem(
            @Param("project") String project,
            @Param("repo") String repo,
            @Param("issueNumber") Integer issueNumber,
            @Param("branch") String branch);

    /** Project-scoped read for the mixed graph, resolving its UUID to the immutable identifier. */
    @Query("SELECT r FROM WorkflowRun r "
            + "WHERE r.project = (SELECT p.identifier FROM Project p WHERE p.id = :projectId) "
            + "ORDER BY r.createdAt DESC, r.id")
    List<WorkflowRun> findForGraphProjection(@Param("projectId") UUID projectId);

    /**
     * Project-scoped run lookup for the run-id write/readback paths (phase events, cost import).
     * A run that exists in a different project resolves to empty, so a foreign caller is treated as
     * not-found and cannot write to or read back another project's run (issue #859 security review).
     */
    Optional<WorkflowRun> findByIdAndProject(UUID id, String project);

    /**
     * Same project-scoped lookup, holding {@code PESSIMISTIC_WRITE} on the run row (issue #1435).
     * Appending a phase event is a check-then-insert: derive the attempt ordinal and the source
     * identity, look for an existing row, insert if absent. Two concurrent deliveries of the same
     * logical fact would otherwise both observe absence, and one would fail at flush against the
     * unique index instead of returning the stored event as the contract promises. Serializing event
     * creation on the parent run makes the whole sequence atomic.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM WorkflowRun r WHERE r.id = :id AND r.project = :project")
    Optional<WorkflowRun> findByIdAndProjectForUpdate(@Param("id") UUID id, @Param("project") String project);

    /**
     * Roll up the runs matching the scope into a single row in the database. Counting, outcome
     * filtering, cycle-time percentiles (minutes between started_at and ended_at), and cost sums all
     * run in Postgres so the read scales with the {@code (project, started_at)} index instead of
     * materializing the window in JVM memory. The time anchor is {@code COALESCE(started_at,
     * created_at)} so runs that have not recorded a start time still fall in a window. All non-window
     * filters are null-guarded ({@code :x IS NULL OR col = :x}); {@code requirement} matches against
     * the {@code workflow_run_requirement_uid} child table.
     */
    @Query(
            value = "SELECT "
                    + " COUNT(*) AS \"totalRuns\","
                    + " COUNT(*) FILTER (WHERE r.outcome = 'MERGED') AS \"mergedRuns\","
                    + " COUNT(*) FILTER (WHERE r.outcome = 'CLOSED_WITHOUT_MERGE') AS \"closedRuns\","
                    + " COUNT(*) FILTER (WHERE r.final_state IN ('RUNNING','READY_FOR_REVIEW')) AS \"activeRuns\","
                    + " COUNT(*) FILTER (WHERE r.final_state = 'ESCALATED') AS \"escalatedRuns\","
                    + " COUNT(*) FILTER (WHERE r.final_state = 'ABANDONED') AS \"abandonedRuns\","
                    + " COUNT(*) FILTER (WHERE r.final_state = 'SUPERSEDED') AS \"supersededRuns\","
                    + " percentile_disc(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.ended_at - r.started_at))/60.0)"
                    + "   FILTER (WHERE r.started_at IS NOT NULL AND r.ended_at IS NOT NULL) AS \"cycleTimeP50Min\","
                    + " percentile_disc(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.ended_at - r.started_at))/60.0)"
                    + "   FILTER (WHERE r.started_at IS NOT NULL AND r.ended_at IS NOT NULL) AS \"cycleTimeP95Min\","
                    + " percentile_disc(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.ended_at - r.started_at))/60.0)"
                    + "   FILTER (WHERE r.started_at IS NOT NULL AND r.ended_at IS NOT NULL) AS \"cycleTimeP99Min\","
                    + " COALESCE(SUM(r.cost_proxy), 0) AS \"totalCostProxy\","
                    + " COALESCE(SUM(r.cost_proxy) FILTER (WHERE r.outcome = 'MERGED'), 0) AS \"mergedCostProxy\","
                    + " COALESCE(SUM(r.cost_proxy) FILTER (WHERE r.outcome = 'CLOSED_WITHOUT_MERGE'), 0) AS \"closedCostProxy\","
                    + " COALESCE(SUM(r.model_invocation_count), 0) AS \"totalModelInvocations\","
                    + " COALESCE(SUM(r.wall_clock_minutes), 0) AS \"totalWallClockMinutes\","
                    + " COALESCE(SUM(r.token_usage), 0) AS \"totalTokenUsage\""
                    + " FROM workflow_run r"
                    + " WHERE COALESCE(r.started_at, r.created_at) >= :from"
                    + "   AND COALESCE(r.started_at, r.created_at) < :to"
                    + "   AND (:project IS NULL OR r.project = :project)"
                    + "   AND (:repo IS NULL OR r.repo = :repo)"
                    + "   AND (:workflowType IS NULL OR r.workflow_type = :workflowType)"
                    + "   AND (:runtime IS NULL OR r.runtime_driver = :runtime)"
                    + "   AND (:outcome IS NULL OR r.outcome = :outcome)"
                    + "   AND (:requirement IS NULL OR EXISTS ("
                    + "        SELECT 1 FROM workflow_run_requirement_uid u"
                    + "        WHERE u.run_id = r.id AND u.requirement_uid = :requirement))",
            nativeQuery = true)
    RunRollupRow aggregateRuns(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("project") String project,
            @Param("repo") String repo,
            @Param("workflowType") String workflowType,
            @Param("runtime") String runtime,
            @Param("outcome") String outcome,
            @Param("requirement") String requirement);

    /** One already-aggregated rollup row for a scoped window. Cycle-time fields are null with no completed runs. */
    interface RunRollupRow {
        long getTotalRuns();

        long getMergedRuns();

        long getClosedRuns();

        long getActiveRuns();

        long getEscalatedRuns();

        long getAbandonedRuns();

        long getSupersededRuns();

        Double getCycleTimeP50Min();

        Double getCycleTimeP95Min();

        Double getCycleTimeP99Min();

        java.math.BigDecimal getTotalCostProxy();

        java.math.BigDecimal getMergedCostProxy();

        java.math.BigDecimal getClosedCostProxy();

        long getTotalModelInvocations();

        long getTotalWallClockMinutes();

        long getTotalTokenUsage();
    }
}
