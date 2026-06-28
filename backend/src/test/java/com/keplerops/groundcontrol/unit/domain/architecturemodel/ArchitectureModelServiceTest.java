package com.keplerops.groundcontrol.unit.domain.architecturemodel;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElement;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementStateRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelSnapshotRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelDiffStatus;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementStateCommand;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelProvenanceSource;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelService;
import com.keplerops.groundcontrol.domain.architecturemodel.service.CreateArchitectureModelSnapshotCommand;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArchitectureModelServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111118");
    private static final UUID SNAPSHOT_A_ID = UUID.fromString("22222222-2222-2222-2222-222222222218");
    private static final UUID SNAPSHOT_B_ID = UUID.fromString("33333333-3333-3333-3333-333333333318");
    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";

    @Mock
    private ArchitectureModelSnapshotRepository snapshotRepository;

    @Mock
    private ArchitectureModelElementRepository elementRepository;

    @Mock
    private ArchitectureModelElementStateRepository stateRepository;

    @Mock
    private ProjectService projectService;

    private ArchitectureModelService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new ArchitectureModelService(snapshotRepository, elementRepository, stateRepository, projectService);
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
    }

    @Test
    void createSnapshotPersistsStableElementsAndFlowState() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(snapshotRepository.existsByProjectIdAndModelVersion(PROJECT_ID, "architecture-model/v1"))
                .thenReturn(false);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> {
            var snapshot = invocation.getArgument(0, ArchitectureModelSnapshot.class);
            setField(snapshot, "id", SNAPSHOT_A_ID);
            setField(snapshot, "createdAt", Instant.parse("2026-06-28T10:00:00Z"));
            setField(snapshot, "updatedAt", Instant.parse("2026-06-28T10:00:00Z"));
            return snapshot;
        });
        when(elementRepository.findByProjectIdAndStableKey(any(), any())).thenReturn(Optional.empty());
        when(elementRepository.save(any())).thenAnswer(invocation -> {
            var element = invocation.getArgument(0, ArchitectureModelElement.class);
            setField(element, "id", UUID.randomUUID());
            return element;
        });
        when(stateRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createSnapshot(new CreateArchitectureModelSnapshotCommand(
                PROJECT_ID,
                "architecture-model/v1",
                COMMIT,
                "MANUAL",
                List.of(
                        state("component:api", ArchitectureModelElementKind.COMPONENT),
                        state("store:postgres", ArchitectureModelElementKind.DATA_STORE),
                        flow("flow:api-postgres", "component:api", "store:postgres"))));

        assertThat(result.snapshot().getModelVersion()).isEqualTo("architecture-model/v1");
        assertThat(result.snapshot().getElementCount()).isEqualTo(3);
        assertThat(result.snapshot().getFlowCount()).isEqualTo(1);
        assertThat(result.states())
                .extracting(ArchitectureModelElementState::getStableKey)
                .containsExactly("component:api", "store:postgres", "flow:api-postgres");

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass((Class<List<ArchitectureModelElementState>>) (Class<?>) List.class);
        verify(stateRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .filteredOn(state -> state.getElementKind() == ArchitectureModelElementKind.DATA_FLOW)
                .singleElement()
                .satisfies(state -> {
                    assertThat(state.getFlowSourceStableKey()).isEqualTo("component:api");
                    assertThat(state.getFlowTargetStableKey()).isEqualTo("store:postgres");
                    assertThat(state.getFlowDirection()).isEqualTo(ArchitectureFlowDirection.UNIDIRECTIONAL);
                });
    }

    @Test
    void createSnapshotRejectsFlowEndpointsMissingFromTheSameSnapshot() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(snapshotRepository.existsByProjectIdAndModelVersion(PROJECT_ID, "architecture-model/v1"))
                .thenReturn(false);

        var command = new CreateArchitectureModelSnapshotCommand(
                PROJECT_ID,
                "architecture-model/v1",
                COMMIT,
                "MANUAL",
                List.of(
                        state("component:api", ArchitectureModelElementKind.COMPONENT),
                        flow("flow:api-missing", "component:api", "store:missing")));

        assertThatThrownBy(() -> service.createSnapshot(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("same snapshot");
    }

    @Test
    void diffSnapshotsClassifiesAddedRemovedChangedAndProvenanceOnlyChanges() {
        var snapshotA = snapshot(SNAPSHOT_A_ID, "architecture-model/a");
        var snapshotB = snapshot(SNAPSHOT_B_ID, "architecture-model/b");
        when(snapshotRepository.findByIdAndProjectId(SNAPSHOT_A_ID, PROJECT_ID)).thenReturn(Optional.of(snapshotA));
        when(snapshotRepository.findByIdAndProjectId(SNAPSHOT_B_ID, PROJECT_ID)).thenReturn(Optional.of(snapshotB));
        when(stateRepository.findBySnapshotIdOrderByStableKey(SNAPSHOT_A_ID))
                .thenReturn(List.of(
                        persistedState(
                                snapshotA, "component:api", ArchitectureModelElementKind.COMPONENT, "API", "adapter-a"),
                        persistedState(
                                snapshotA,
                                "component:worker",
                                ArchitectureModelElementKind.COMPONENT,
                                "Worker",
                                "adapter-a"),
                        persistedState(
                                snapshotA,
                                "store:postgres",
                                ArchitectureModelElementKind.DATA_STORE,
                                "Postgres",
                                "adapter-a")));
        when(stateRepository.findBySnapshotIdOrderByStableKey(SNAPSHOT_B_ID))
                .thenReturn(List.of(
                        persistedState(
                                snapshotB,
                                "component:api",
                                ArchitectureModelElementKind.COMPONENT,
                                "API v2",
                                "adapter-a"),
                        persistedState(
                                snapshotB, "component:web", ArchitectureModelElementKind.COMPONENT, "Web", "adapter-a"),
                        persistedState(
                                snapshotB,
                                "store:postgres",
                                ArchitectureModelElementKind.DATA_STORE,
                                "Postgres",
                                "adapter-b")));

        var diff = service.diff(PROJECT_ID, SNAPSHOT_A_ID, SNAPSHOT_B_ID);

        assertThat(diff.entries())
                .extracting(entry -> entry.stableKey() + ":" + entry.status())
                .containsExactly(
                        "component:api:" + ArchitectureModelDiffStatus.CHANGED,
                        "component:web:" + ArchitectureModelDiffStatus.ADDED,
                        "component:worker:" + ArchitectureModelDiffStatus.REMOVED,
                        "store:postgres:" + ArchitectureModelDiffStatus.PROVENANCE_ONLY_CHANGED);
    }

    private static ArchitectureModelSnapshot snapshot(UUID id, String version) {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var snapshot = new ArchitectureModelSnapshot(project, null, version, COMMIT, "MANUAL", "codex");
        setField(snapshot, "id", id);
        return snapshot;
    }

    private static ArchitectureModelElementState persistedState(
            ArchitectureModelSnapshot snapshot,
            String stableKey,
            ArchitectureModelElementKind kind,
            String label,
            String adapterId) {
        var element = new ArchitectureModelElement(snapshot.getProject(), stableKey, kind);
        setField(element, "id", UUID.randomUUID());
        return new ArchitectureModelElementState(
                snapshot.getProject(),
                snapshot,
                element,
                command(stableKey, kind, label, null, null, null, null, null, null, adapterId));
    }

    private static ArchitectureModelElementStateCommand state(String stableKey, ArchitectureModelElementKind kind) {
        return command(stableKey, kind, stableKey, null, null, null, null, null, null, "adapter-a");
    }

    private static ArchitectureModelElementStateCommand flow(String stableKey, String sourceKey, String targetKey) {
        return command(
                stableKey,
                ArchitectureModelElementKind.DATA_FLOW,
                "API to Postgres",
                null,
                null,
                sourceKey,
                targetKey,
                ArchitectureFlowDirection.UNIDIRECTIONAL,
                null,
                "adapter-a");
    }

    private static ArchitectureModelElementStateCommand command(
            String stableKey,
            ArchitectureModelElementKind kind,
            String label,
            String trustBoundaryKey,
            String dataClassificationKey,
            String flowSourceKey,
            String flowTargetKey,
            ArchitectureFlowDirection flowDirection,
            Map<String, Object> payload,
            String adapterId) {
        return new ArchitectureModelElementStateCommand(
                stableKey,
                kind,
                label,
                "summary",
                "backend/src/main/java/App.java",
                trustBoundaryKey,
                dataClassificationKey,
                flowSourceKey,
                flowTargetKey,
                flowDirection,
                ArchitectureModelProvenanceSource.ADAPTER,
                stableKey,
                adapterId,
                "stub-deriver",
                "0.1.0",
                "stub-rules",
                "2026.06",
                null,
                COMMIT,
                payload == null ? Map.of() : payload);
    }
}
