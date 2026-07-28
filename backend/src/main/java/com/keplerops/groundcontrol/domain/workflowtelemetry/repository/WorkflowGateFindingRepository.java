package com.keplerops.groundcontrol.domain.workflowtelemetry.repository;

import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowGateFinding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Project-scoped access to gate findings (issue #1355).
 *
 * <p>Every lookup carries {@code project} so a caller cannot read or resolve another project's
 * findings by guessing a UUID: an event or finding id is an identifier, never an authorization
 * capability.
 */
public interface WorkflowGateFindingRepository extends JpaRepository<WorkflowGateFinding, UUID> {

    List<WorkflowGateFinding> findByPhaseEventIdAndProject(UUID phaseEventId, String project);

    Optional<WorkflowGateFinding> findByIdAndProject(UUID id, String project);

    Optional<WorkflowGateFinding> findByPhaseEventIdAndFindingKey(UUID phaseEventId, String findingKey);

    boolean existsByPhaseEventId(UUID phaseEventId);

    /**
     * Finding counts by reviewer/detector, category, severity, and disposition.
     *
     * <p>Grouped in the database rather than in memory so a large window is not materialized into
     * the service to be counted. Severity and category are nullable by design — a source that did
     * not express one is a real observation, and the aggregate reports that bucket rather than
     * dropping the row.
     */
    @Query(
            """
            select f.stationId, f.sourceKind, f.sourceId, f.category, f.severity, f.disposition, count(f)
              from WorkflowGateFinding f
             where f.project = :project
               and f.occurredAt >= :from
               and f.occurredAt < :to
             group by f.stationId, f.sourceKind, f.sourceId, f.category, f.severity, f.disposition
             order by f.stationId, f.sourceId
            """)
    List<Object[]> aggregateByProject(
            @Param("project") String project, @Param("from") Instant from, @Param("to") Instant to);

    /** Findings for one run, used to report per-run open-disposition coverage. */
    List<WorkflowGateFinding> findByRunIdAndProject(UUID runId, String project);
}
