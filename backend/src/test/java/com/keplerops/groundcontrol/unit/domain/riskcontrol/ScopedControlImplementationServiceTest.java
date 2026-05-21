package com.keplerops.groundcontrol.unit.domain.riskcontrol;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.service.CreateScopedControlImplementationCommand;
import com.keplerops.groundcontrol.domain.riskcontrol.service.ScopedControlImplementationService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.UpdateScopedControlImplementationCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for ScopedControlImplementationService (GC-T003 C1). */
@ExtendWith(MockitoExtension.class)
class ScopedControlImplementationServiceTest {

    @Mock
    private ScopedControlImplementationRepository repository;

    @Mock
    private ProjectService projectService;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @InjectMocks
    private ScopedControlImplementationService service;

    private Project project;
    private UUID projectId;
    private Control control;
    private UUID controlId;

    @BeforeEach
    void setUp() {
        project = new Project("test-project", "Test Project");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);

        control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        controlId = UUID.randomUUID();
        setField(control, "id", controlId);
    }

    @Nested
    class Create {

        @Test
        void creates_withRequiredFields() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(repository.existsByProjectIdAndUid(projectId, "SCI-001")).thenReturn(false);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateScopedControlImplementationCommand(
                    projectId, "SCI-001", controlId, "Email Gateway", null, null);

            var result = service.create(cmd);

            assertThat(result).isNotNull();
            assertThat(result.getUid()).isEqualTo("SCI-001");
            assertThat(result.getControl()).isEqualTo(control);
            assertThat(result.getName()).isEqualTo("Email Gateway");
            verify(repository).save(any(ScopedControlImplementation.class));
        }

        @Test
        void creates_withImplementationScopeAndAsset() {
            var asset = new OperationalAsset(project, "ASSET-001", "Web Server");
            var assetId = UUID.randomUUID();
            setField(asset, "id", assetId);

            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(repository.existsByProjectIdAndUid(projectId, "SCI-002")).thenReturn(false);
            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.of(asset));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateScopedControlImplementationCommand(
                    projectId, "SCI-002", controlId, "Web Gateway Impl", "Perimeter only", assetId);

            var result = service.create(cmd);

            assertThat(result.getImplementationScope()).isEqualTo("Perimeter only");
            assertThat(result.getOperationalAsset()).isEqualTo(asset);
        }

        @Test
        void throwsConflict_whenUidAlreadyExists() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(repository.existsByProjectIdAndUid(projectId, "SCI-001")).thenReturn(true);

            var cmd = new CreateScopedControlImplementationCommand(
                    projectId, "SCI-001", controlId, "Duplicate", null, null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(ConflictException.class);
        }

        @Test
        void throwsNotFound_whenControlNotInProject() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.empty());

            var cmd = new CreateScopedControlImplementationCommand(
                    projectId, "SCI-001", controlId, "Missing Control", null, null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFound_whenAssetNotInProject() {
            var assetId = UUID.randomUUID();
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(repository.existsByProjectIdAndUid(projectId, "SCI-001")).thenReturn(false);
            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.empty());

            var cmd = new CreateScopedControlImplementationCommand(
                    projectId, "SCI-001", controlId, "Name", null, assetId);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Update {

        @Test
        void updatesNameAndScope() {
            var sciId = UUID.randomUUID();
            var sci = new ScopedControlImplementation(project, "SCI-001", control, "Old Name");
            setField(sci, "id", sciId);

            when(repository.findByIdAndProjectId(sciId, projectId)).thenReturn(Optional.of(sci));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateScopedControlImplementationCommand(projectId, sciId, "New Name", "New Scope", null);

            var result = service.update(cmd);

            assertThat(result.getName()).isEqualTo("New Name");
            assertThat(result.getImplementationScope()).isEqualTo("New Scope");
            verify(repository).save(sci);
        }

        @Test
        void updatesAsset() {
            var sciId = UUID.randomUUID();
            var sci = new ScopedControlImplementation(project, "SCI-001", control, "Name");
            setField(sci, "id", sciId);

            var asset = new OperationalAsset(project, "ASSET-001", "Server");
            var assetId = UUID.randomUUID();
            setField(asset, "id", assetId);

            when(repository.findByIdAndProjectId(sciId, projectId)).thenReturn(Optional.of(sci));
            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.of(asset));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateScopedControlImplementationCommand(projectId, sciId, null, null, assetId);

            var result = service.update(cmd);

            assertThat(result.getOperationalAsset()).isEqualTo(asset);
        }

        @Test
        void throwsNotFound_whenSciNotInProject() {
            var sciId = UUID.randomUUID();
            when(repository.findByIdAndProjectId(sciId, projectId)).thenReturn(Optional.empty());

            var cmd = new UpdateScopedControlImplementationCommand(projectId, sciId, "Name", null, null);

            assertThatThrownBy(() -> service.update(cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFound_whenUpdatedAssetNotInProject() {
            var sciId = UUID.randomUUID();
            var sci = new ScopedControlImplementation(project, "SCI-001", control, "Name");
            setField(sci, "id", sciId);
            var assetId = UUID.randomUUID();

            when(repository.findByIdAndProjectId(sciId, projectId)).thenReturn(Optional.of(sci));
            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.empty());

            var cmd = new UpdateScopedControlImplementationCommand(projectId, sciId, null, null, assetId);

            assertThatThrownBy(() -> service.update(cmd)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesExistingSci() {
            var sciId = UUID.randomUUID();
            var sci = new ScopedControlImplementation(project, "SCI-001", control, "Name");
            setField(sci, "id", sciId);

            when(repository.findByIdAndProjectId(sciId, projectId)).thenReturn(Optional.of(sci));

            service.delete(projectId, sciId);

            verify(repository).delete(sci);
        }

        @Test
        void throwsNotFound_whenSciAbsent() {
            var sciId = UUID.randomUUID();
            when(repository.findByIdAndProjectId(sciId, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(projectId, sciId)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ReadQueries {

        @Test
        void getById_returnsExistingSci() {
            var sciId = UUID.randomUUID();
            var sci = new ScopedControlImplementation(project, "SCI-001", control, "Name");
            setField(sci, "id", sciId);

            when(repository.findByIdAndProjectId(sciId, projectId)).thenReturn(Optional.of(sci));

            var result = service.getById(projectId, sciId);

            assertThat(result).isEqualTo(sci);
        }

        @Test
        void getById_throwsNotFound_whenAbsent() {
            var sciId = UUID.randomUUID();
            when(repository.findByIdAndProjectId(sciId, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(projectId, sciId)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void listByProject_delegatesToRepository() {
            var sci = new ScopedControlImplementation(project, "SCI-001", control, "Name");
            when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(sci));

            var result = service.listByProject(projectId);

            assertThat(result).containsExactly(sci);
        }

        @Test
        void listByProjectAndControl_delegatesToRepository() {
            var sci = new ScopedControlImplementation(project, "SCI-001", control, "Name");
            when(repository.findByProjectIdAndControlIdOrderByCreatedAtDesc(projectId, controlId))
                    .thenReturn(List.of(sci));

            var result = service.listByProjectAndControl(projectId, controlId);

            assertThat(result).containsExactly(sci);
        }
    }
}
