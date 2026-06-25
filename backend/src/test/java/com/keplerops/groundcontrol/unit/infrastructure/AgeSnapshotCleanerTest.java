package com.keplerops.groundcontrol.unit.infrastructure;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.infrastructure.age.AgeGraphSnapshotRepository;
import com.keplerops.groundcontrol.infrastructure.age.AgeSnapshotCleaner;
import com.keplerops.groundcontrol.infrastructure.age.GraphPublicationProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AgeSnapshotCleanerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AgeGraphSnapshotRepository snapshotRepository;

    private AgeSnapshotCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new AgeSnapshotCleaner(jdbcTemplate, snapshotRepository, new GraphPublicationProperties(2, 300L));
    }

    @Test
    void cleanup_dropsStaleSnapshotGraphsAndForgetsTheirMetadata() {
        when(snapshotRepository.graphsToDrop(2, 300L)).thenReturn(List.of("requirements_v1", "requirements_v2"));

        cleaner.cleanup();

        verify(jdbcTemplate).execute("LOAD 'age'");
        verify(jdbcTemplate).execute("SET search_path = ag_catalog, \"$user\", public");
        verify(jdbcTemplate).execute("SELECT drop_graph('requirements_v1', true)");
        verify(jdbcTemplate).execute("SELECT drop_graph('requirements_v2', true)");
        verify(snapshotRepository).deleteByGraphName("requirements_v1");
        verify(snapshotRepository).deleteByGraphName("requirements_v2");
    }

    @Test
    void cleanup_isNoOpWhenNothingToRetire() {
        when(snapshotRepository.graphsToDrop(2, 300L)).thenReturn(List.of());

        cleaner.cleanup();

        // No AGE session setup or drops when there is nothing beyond the retention window.
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void cleanup_isBestEffortWhenADropFails() {
        when(snapshotRepository.graphsToDrop(2, 300L)).thenReturn(List.of("requirements_v1", "requirements_v2"));
        lenient()
                .doThrow(new RuntimeException("lock timeout"))
                .when(jdbcTemplate)
                .execute(contains("drop_graph('requirements_v1'"));

        cleaner.cleanup();

        // The failed drop does not abort the loop and its metadata row is left for a later attempt;
        // the next snapshot is still dropped and forgotten.
        verify(snapshotRepository, never()).deleteByGraphName("requirements_v1");
        verify(jdbcTemplate).execute("SELECT drop_graph('requirements_v2', true)");
        verify(snapshotRepository).deleteByGraphName("requirements_v2");
    }
}
