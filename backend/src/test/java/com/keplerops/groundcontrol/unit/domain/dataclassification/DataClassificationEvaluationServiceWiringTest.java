package com.keplerops.groundcontrol.unit.domain.dataclassification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementStateRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelSnapshotRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationEvaluationService;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeService;
import com.keplerops.groundcontrol.domain.dataclassification.service.DefaultDataClassificationLattice;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationFindingReason;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Exercises the repository wiring of the evaluation service (GC-GRC-006): resolving the active
 * lattice, selecting the latest or a named snapshot, projecting element state into views, and the
 * empty/not-found edges. The pure allow/deny logic is covered separately by
 * {@code DataClassificationEvaluationServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class DataClassificationEvaluationServiceWiringTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-1111111111aa");
    private static final UUID SNAPSHOT_ID = UUID.fromString("33333333-3333-3333-3333-333333333ccc");

    @Mock
    private DataClassificationLatticeService latticeService;

    @Mock
    private ArchitectureModelSnapshotRepository snapshotRepository;

    @Mock
    private ArchitectureModelElementStateRepository stateRepository;

    @InjectMocks
    private DataClassificationEvaluationService service;

    @BeforeEach
    void setUp() {
        when(latticeService.resolveActiveDefinition(PROJECT_ID))
                .thenReturn(DefaultDataClassificationLattice.definition());
    }

    private static ArchitectureModelElementState state(
            String stableKey,
            ArchitectureModelElementKind kind,
            String label,
            String source,
            String target,
            ArchitectureFlowDirection direction) {
        var state = mock(ArchitectureModelElementState.class);
        when(state.getStableKey()).thenReturn(stableKey);
        when(state.getElementKind()).thenReturn(kind);
        when(state.getDataClassificationKey()).thenReturn(label);
        when(state.getFlowSourceStableKey()).thenReturn(source);
        when(state.getFlowTargetStableKey()).thenReturn(target);
        when(state.getFlowDirection()).thenReturn(direction);
        return state;
    }

    @Test
    void evaluateLatestReturnsEmptyResultWhenProjectHasNoSnapshot() {
        when(snapshotRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());

        var result = service.evaluateLatest(PROJECT_ID);

        assertThat(result.evaluatedFlowCount()).isZero();
        assertThat(result.modelVersion()).isNull();
        assertThat(result.snapshotId()).isNull();
        assertThat(result.violations()).isEmpty();
        assertThat(result.limitations()).isEmpty();
    }

    @Test
    void evaluateLatestEvaluatesTheMostRecentSnapshot() {
        var snapshot = mock(ArchitectureModelSnapshot.class);
        when(snapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(snapshot.getModelVersion()).thenReturn("architecture-model/v1");
        // Build the mocked states before opening the stubbing below: the state() helper stubs each
        // mock, and Mockito's when() is not reentrant inside an unfinished when(...).thenReturn(...).
        var states = List.of(
                state("db.users", ArchitectureModelElementKind.DATA_STORE, "PII", null, null, null),
                state("log.app", ArchitectureModelElementKind.DATA_STORE, "PUBLIC", null, null, null),
                state(
                        "flow.users-to-log",
                        ArchitectureModelElementKind.DATA_FLOW,
                        null,
                        "db.users",
                        "log.app",
                        ArchitectureFlowDirection.UNIDIRECTIONAL));
        when(snapshotRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(snapshot));
        when(stateRepository.findBySnapshotIdOrderByStableKey(SNAPSHOT_ID)).thenReturn(states);

        var result = service.evaluateLatest(PROJECT_ID);

        assertThat(result.modelVersion()).isEqualTo("architecture-model/v1");
        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.evaluatedFlowCount()).isEqualTo(1);
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst().reason())
                .isEqualTo(DataClassificationFindingReason.LABEL_FLOW_NOT_PERMITTED);
    }

    @Test
    void evaluateSnapshotResolvesByIdScopedToProject() {
        var snapshot = mock(ArchitectureModelSnapshot.class);
        when(snapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(snapshot.getModelVersion()).thenReturn("architecture-model/v1");
        when(snapshotRepository.findByIdAndProjectId(SNAPSHOT_ID, PROJECT_ID)).thenReturn(Optional.of(snapshot));
        when(stateRepository.findBySnapshotIdOrderByStableKey(SNAPSHOT_ID)).thenReturn(List.of());

        var result = service.evaluateSnapshot(PROJECT_ID, SNAPSHOT_ID);

        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.evaluatedFlowCount()).isZero();
    }

    @Test
    void evaluateSnapshotThrowsWhenSnapshotMissing() {
        when(snapshotRepository.findByIdAndProjectId(SNAPSHOT_ID, PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateSnapshot(PROJECT_ID, SNAPSHOT_ID))
                .isInstanceOf(NotFoundException.class);
    }
}
