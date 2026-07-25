package com.keplerops.groundcontrol.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.infrastructure.age.AgeGraphSnapshotRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AgeGraphSnapshotRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AgeGraphSnapshotRepository repository;

    @Test
    void nextVersion_returnsSequenceValue() {
        when(jdbcTemplate.queryForObject("SELECT nextval('age_graph_snapshot_version_seq')", Long.class))
                .thenReturn(7L);

        assertThat(repository.nextVersion()).isEqualTo(7L);
    }

    @Test
    void nextVersion_throwsWhenSequenceReturnsNull() {
        when(jdbcTemplate.queryForObject("SELECT nextval('age_graph_snapshot_version_seq')", Long.class))
                .thenReturn(null);

        assertThatThrownBy(() -> repository.nextVersion()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findActiveGraphName_returnsGreatestVersionRow() {
        when(jdbcTemplate.queryForObject(
                        "SELECT graph_name FROM age_graph_snapshot ORDER BY version DESC LIMIT 1", String.class))
                .thenReturn("requirements_v9");

        assertThat(repository.findActiveGraphName()).contains("requirements_v9");
    }

    @Test
    void findActiveGraphName_emptyWhenNoSnapshots() {
        when(jdbcTemplate.queryForObject(
                        "SELECT graph_name FROM age_graph_snapshot ORDER BY version DESC LIMIT 1", String.class))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(repository.findActiveGraphName()).isEmpty();
    }

    @Test
    void insertSnapshot_bindsAllColumnsIncludingSourceRevision() {
        repository.insertSnapshot(3L, "requirements_v3", "GLOBAL", 12, 5, 77, "alice");

        // clock_timestamp() (not now(), which is transaction-start time) so published_at
        // reflects the actual publication instant even for a long-running materialization —
        // AgeSnapshotCleaner's retirement grace window is measured from this column.
        verify(jdbcTemplate)
                .update(
                        "INSERT INTO age_graph_snapshot "
                                + "(version, graph_name, scope, node_count, edge_count, source_revision, published_at, published_by) "
                                + "VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(), ?)",
                        3L,
                        "requirements_v3",
                        "GLOBAL",
                        12,
                        5,
                        77,
                        "alice");
    }

    @Test
    void insertSnapshot_bindsNullSourceRevisionWhenNoRevisionResolved() {
        // No Envers revision has ever been created yet (fresh database) — source_revision must
        // be recorded as NULL, not a fabricated 0 or -1 coordinate.
        repository.insertSnapshot(1L, "requirements_v1", "GLOBAL", 0, 0, null, "bootstrap");

        verify(jdbcTemplate)
                .update(
                        "INSERT INTO age_graph_snapshot "
                                + "(version, graph_name, scope, node_count, edge_count, source_revision, published_at, published_by) "
                                + "VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(), ?)",
                        1L,
                        "requirements_v1",
                        "GLOBAL",
                        0,
                        0,
                        null,
                        "bootstrap");
    }

    @Test
    void graphsToDrop_keepsNewestRetainedAndAppliesRetirementAgeGrace() {
        // The grace is measured from retirement (the successor's published_at via lead()), not from
        // the snapshot's own publication — so a long-active snapshot superseded just now is safe.
        when(jdbcTemplate.queryForList(
                        "SELECT graph_name FROM "
                                + "(SELECT graph_name, version, lead(published_at) OVER (ORDER BY version) AS retired_at "
                                + "FROM age_graph_snapshot) s "
                                + "WHERE graph_name NOT IN "
                                + "(SELECT graph_name FROM age_graph_snapshot ORDER BY version DESC LIMIT ?) "
                                + "AND retired_at IS NOT NULL "
                                + "AND retired_at < now() - make_interval(secs => ?) "
                                + "ORDER BY version",
                        String.class,
                        2,
                        300.0))
                .thenReturn(List.of("requirements_v1"));

        assertThat(repository.graphsToDrop(2, 300L)).containsExactly("requirements_v1");
    }

    @Test
    void deleteByGraphName_deletesMetadataRow() {
        repository.deleteByGraphName("requirements_v1");

        verify(jdbcTemplate).update("DELETE FROM age_graph_snapshot WHERE graph_name = ?", "requirements_v1");
    }
}
