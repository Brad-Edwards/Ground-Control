package com.keplerops.groundcontrol.infrastructure.age;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Best-effort cleanup of superseded AGE graph snapshots (ADR-062). Runs after a publication
 * commits — the publisher registers it as an after-commit synchronization — so old snapshots are
 * dropped only once the active-pointer swap is visible, never before; dropping a still-resolvable
 * snapshot is exactly the empty-graph failure mode this ADR removes.
 *
 * <p>Cleanup is never on the reader path and never fails a publication: it runs in its own
 * transaction (so AGE's connection-local {@code LOAD 'age'} / {@code search_path} apply to the
 * same connection as {@code drop_graph}), and each drop is isolated so a single failure is logged
 * and skipped, leaving that snapshot's metadata row for a later attempt.
 */
@Component
public class AgeSnapshotCleaner {

    private static final Logger log = LoggerFactory.getLogger(AgeSnapshotCleaner.class);

    private final JdbcTemplate jdbcTemplate;
    private final AgeGraphSnapshotRepository snapshotRepository;
    private final GraphPublicationProperties publicationProperties;

    public AgeSnapshotCleaner(
            JdbcTemplate jdbcTemplate,
            AgeGraphSnapshotRepository snapshotRepository,
            GraphPublicationProperties publicationProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotRepository = snapshotRepository;
        this.publicationProperties = publicationProperties;
    }

    /** Drop snapshot graphs outside the retention window and forget their metadata. */
    @Transactional
    public void cleanup() {
        var staleGraphs = snapshotRepository.graphsToDrop(
                publicationProperties.retainedSnapshots(), publicationProperties.minRetainedAgeSeconds());
        if (staleGraphs.isEmpty()) {
            return;
        }
        jdbcTemplate.execute("LOAD 'age'");
        jdbcTemplate.execute("SET search_path = ag_catalog, \"$user\", public");
        for (String graphName : staleGraphs) {
            try {
                // graphName is allowlist-validated before it reaches the drop_graph SQL literal
                // (ADR-032); the second argument true cascades the schema drop.
                jdbcTemplate.execute("SELECT drop_graph('" + AgeIdentifiers.validateGraphName(graphName) + "', true)");
                snapshotRepository.deleteByGraphName(graphName);
            } catch (RuntimeException dropFailed) {
                log.warn("graph_snapshot_cleanup_skipped: graph={} reason={}", graphName, dropFailed.getMessage());
            }
        }
    }
}
