package com.keplerops.groundcontrol.infrastructure.age;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the AGE graph snapshot pointer + metadata table (ADR-062). Publication is
 * INSERT-only: each materialization writes one new row, and the active snapshot a reader queries
 * is the row with the greatest {@code version}. The "pointer swap" is therefore the new row
 * becoming visible at the publisher's transaction commit — the previously-active snapshot row is
 * never mutated, so there is no read-then-update conflict under repeatable-read isolation.
 *
 * <p>This is plain relational bookkeeping (no AGE/Cypher), kept in the AGE infrastructure package
 * alongside the adapter that owns it. The {@code scope}/{@code state}-free, INSERT-only shape is
 * deliberate; see ADR-062.
 */
@Repository
public class AgeGraphSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgeGraphSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Next monotonic snapshot version, used to build a unique snapshot graph name and to order
     * snapshots. {@code nextval} is non-transactional, so a rolled-back publication still
     * consumes the version — that is fine, names only need to be unique, not contiguous.
     */
    public long nextVersion() {
        Long version = jdbcTemplate.queryForObject("SELECT nextval('age_graph_snapshot_version_seq')", Long.class);
        if (version == null) {
            throw new IllegalStateException("age_graph_snapshot_version_seq returned null");
        }
        return version;
    }

    /** Graph name of the currently-active (greatest-version) snapshot, or empty when none exists. */
    public Optional<String> findActiveGraphName() {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT graph_name FROM age_graph_snapshot ORDER BY version DESC LIMIT 1", String.class));
        } catch (EmptyResultDataAccessException noRows) {
            return Optional.empty();
        }
    }

    /**
     * Record a newly-published snapshot. The greatest-version row is the active one.
     *
     * <p>{@code sourceRevision} is the Envers revision (ADR-084 §5) visible to the
     * REPEATABLE_READ transaction that built this snapshot's projection, or {@code null} when no
     * revision has ever been created yet (a fresh database with nothing audited). It is a
     * relational coordinate, never inferred or fabricated here.
     *
     * <p>{@code published_at} uses {@code clock_timestamp()}, not {@code now()} (which is
     * transaction-start time): a long-running materialization would otherwise understate the
     * actual publication instant, which also shortens {@code AgeSnapshotCleaner}'s retirement
     * grace window (measured from {@code lead(published_at)}) that protects a mid-read snapshot.
     */
    public void insertSnapshot(
            long version,
            String graphName,
            String scope,
            int nodeCount,
            int edgeCount,
            Integer sourceRevision,
            String publishedBy) {
        jdbcTemplate.update(
                "INSERT INTO age_graph_snapshot "
                        + "(version, graph_name, scope, node_count, edge_count, source_revision, published_at, published_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(), ?)",
                version,
                graphName,
                scope,
                nodeCount,
                edgeCount,
                sourceRevision,
                publishedBy);
    }

    /**
     * Snapshot graph names safe to drop: those that are BOTH outside the newest-{@code
     * retainedSnapshots} window AND retired (superseded) more than {@code minAgeSeconds} ago. The
     * count bound keeps the active snapshot plus a buffer of recent ones; the age bound is the
     * grace period that keeps a snapshot a live reader may have just resolved from being dropped
     * mid-read (the metadata lookup is repeatable-read consistent, but that does not extend the AGE
     * graph object's lifetime).
     *
     * <p>The grace is measured from when a snapshot stopped being active, NOT from when it was
     * published: a snapshot can be active for hours and then be superseded and read concurrently,
     * so a publish-time grace would not protect it. A snapshot's retirement instant is the
     * publication instant of its successor (the next-higher version), computed here with {@code
     * lead(published_at) OVER (ORDER BY version)}. The active snapshot (greatest version) has no
     * successor, so its {@code retired_at} is NULL and it is never eligible.
     */
    public List<String> graphsToDrop(int retainedSnapshots, long minAgeSeconds) {
        int keep = Math.max(retainedSnapshots, 0);
        return jdbcTemplate.queryForList(
                "SELECT graph_name FROM "
                        + "(SELECT graph_name, version, lead(published_at) OVER (ORDER BY version) AS retired_at "
                        + "FROM age_graph_snapshot) s "
                        + "WHERE graph_name NOT IN "
                        + "(SELECT graph_name FROM age_graph_snapshot ORDER BY version DESC LIMIT ?) "
                        + "AND retired_at IS NOT NULL "
                        + "AND retired_at < now() - make_interval(secs => ?) "
                        + "ORDER BY version",
                String.class,
                keep,
                (double) minAgeSeconds);
    }

    public void deleteByGraphName(String graphName) {
        jdbcTemplate.update("DELETE FROM age_graph_snapshot WHERE graph_name = ?", graphName);
    }
}
