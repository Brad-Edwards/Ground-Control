package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.projects.service.CreateProjectCommand;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.projects.service.UpdateProjectCommand;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContributionType;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ResearchIntakeService researchIntakeService;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, researchIntakeService);
    }

    private Project makeProject(String identifier, String name) {
        var project = new Project(identifier, name);
        TestUtil.setField(project, "id", UUID.randomUUID());
        return project;
    }

    private Project makeProjectWithId(UUID id, String identifier, String name) {
        var project = new Project(identifier, name);
        TestUtil.setField(project, "id", id);
        return project;
    }

    @Nested
    class Create {

        @Test
        void createsProjectSuccessfully() {
            var command = new CreateProjectCommand("my-project", "My Project", "A description");
            when(projectRepository.existsByIdentifier("my-project")).thenReturn(false);
            when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.create(command);
            assertThat(result.getIdentifier()).isEqualTo("my-project");
            assertThat(result.getName()).isEqualTo("My Project");
            assertThat(result.getDescription()).isEqualTo("A description");
        }

        @Test
        void createsProjectWithNullDescription() {
            var command = new CreateProjectCommand("my-project", "My Project", null);
            when(projectRepository.existsByIdentifier("my-project")).thenReturn(false);
            when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.create(command);
            assertThat(result.getIdentifier()).isEqualTo("my-project");
        }

        @Test
        void throwsConflictForDuplicateIdentifier() {
            var command = new CreateProjectCommand("existing", "Existing", null);
            when(projectRepository.existsByIdentifier("existing")).thenReturn(true);

            assertThatThrownBy(() -> service.create(command)).isInstanceOf(ConflictException.class);
        }

        @Test
        void researchType_withIntake_delegatesToIntakeService() {
            var intakeCmd = new ResearchIntakeCommand(
                    "Investigate research goal",
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    List.of("cite_resolve"),
                    null,
                    null,
                    null,
                    null);
            var command = new CreateProjectCommand(
                    "research-project", "Research Project", "desc", ProjectType.RESEARCH, intakeCmd);
            when(projectRepository.existsByIdentifier("research-project")).thenReturn(false);
            when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.create(command);
            assertThat(result.getType()).isEqualTo(ProjectType.RESEARCH);
            verify(researchIntakeService, times(1)).create(result, intakeCmd);
        }

        @Test
        void researchType_missingIntake_throwsValidation() {
            var command =
                    new CreateProjectCommand("research-project", "Research Project", null, ProjectType.RESEARCH, null);
            when(projectRepository.existsByIdentifier("research-project")).thenReturn(false);

            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(com.keplerops.groundcontrol.domain.exception.DomainValidationException.class)
                    .hasMessageContaining("researchIntake");
            verify(researchIntakeService, never()).create(any(), any());
        }

        @Test
        void softwareType_withIntake_throwsValidation() {
            var intakeCmd = new ResearchIntakeCommand(
                    "x",
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    List.of(),
                    null,
                    null,
                    null,
                    null);
            var command = new CreateProjectCommand("sw-project", "SW Project", null, ProjectType.SOFTWARE, intakeCmd);
            when(projectRepository.existsByIdentifier("sw-project")).thenReturn(false);

            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(com.keplerops.groundcontrol.domain.exception.DomainValidationException.class)
                    .hasMessageContaining("researchIntake");
            verify(researchIntakeService, never()).create(any(), any());
        }

        @Test
        void grcType_onCreate_throwsValidationAndDoesNotPersist() {
            // ADR-089 §4: GRC is not offered for new creation. The service must reject it
            // before persisting, so a caller can never create a brand-new GRC project.
            var command = new CreateProjectCommand("grc-project", "GRC Project", null, ProjectType.GRC, null);
            when(projectRepository.existsByIdentifier("grc-project")).thenReturn(false);

            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("GRC")
                    .extracting("errorCode")
                    .isEqualTo("project_type_grc_not_creatable");
            verify(projectRepository, never()).save(any(Project.class));
            verify(researchIntakeService, never()).create(any(), any());
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsProjectWhenFound() {
            var project = makeProjectWithId(PROJECT_ID, "test", "Test");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

            var result = service.getById(PROJECT_ID);
            assertThat(result.getIdentifier()).isEqualTo("test");
        }

        @Test
        void throwsNotFoundWhenMissing() {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(PROJECT_ID)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class GetByIdentifier {

        @Test
        void returnsProjectWhenFound() {
            var project = makeProject("test", "Test");
            when(projectRepository.findByIdentifier("test")).thenReturn(Optional.of(project));

            var result = service.getByIdentifier("test");
            assertThat(result.getIdentifier()).isEqualTo("test");
        }

        @Test
        void throwsNotFoundWhenMissing() {
            when(projectRepository.findByIdentifier("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getByIdentifier("missing")).isInstanceOf(NotFoundException.class);
        }

        @Test
        void persistedGrcProject_readsBackWithGrcType() {
            // ADR-089 §4: create is closed to GRC, but rows persisted before the guard
            // (legacy GRC projects) must remain readable with their original type.
            var project = new Project("legacy-grc", "Legacy GRC", ProjectType.GRC);
            TestUtil.setField(project, "id", UUID.randomUUID());
            when(projectRepository.findByIdentifier("legacy-grc")).thenReturn(Optional.of(project));

            var result = service.getByIdentifier("legacy-grc");
            assertThat(result.getType()).isEqualTo(ProjectType.GRC);
        }
    }

    @Nested
    class ListProjects {

        @Test
        void returnsAllProjects() {
            var projects = List.of(makeProject("p1", "Project 1"), makeProject("p2", "Project 2"));
            when(projectRepository.findAll()).thenReturn(projects);

            var result = service.list();
            assertThat(result).hasSize(2);
        }
    }

    @Nested
    class UpdateByIdentifier {

        @Test
        void updatesNameAndDescription() {
            var project = makeProject("test", "Old Name");
            when(projectRepository.findByIdentifier("test")).thenReturn(Optional.of(project));
            when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateProjectCommand("New Name", "New Description");
            var result = service.updateByIdentifier("test", command);
            assertThat(result.getName()).isEqualTo("New Name");
            assertThat(result.getDescription()).isEqualTo("New Description");
        }

        @Test
        void nullFieldsAreNotUpdated() {
            var project = makeProject("test", "Old Name");
            project.setDescription("Old Desc");
            when(projectRepository.findByIdentifier("test")).thenReturn(Optional.of(project));
            when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateProjectCommand(null, null);
            var result = service.updateByIdentifier("test", command);
            assertThat(result.getName()).isEqualTo("Old Name");
            assertThat(result.getDescription()).isEqualTo("Old Desc");
        }
    }

    @Nested
    class Update {

        @Test
        void updatesById() {
            var project = makeProjectWithId(PROJECT_ID, "test", "Old Name");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateProjectCommand("New Name", "New Desc");
            var result = service.update(PROJECT_ID, command);
            assertThat(result.getName()).isEqualTo("New Name");
        }
    }

    @Nested
    class ResolveProject {

        @Test
        void resolvesWithExplicitIdentifier() {
            var project = makeProject("ground-control", "Ground Control");
            when(projectRepository.findByIdentifier("ground-control")).thenReturn(Optional.of(project));

            var result = service.resolveProject("ground-control");
            assertThat(result.getIdentifier()).isEqualTo("ground-control");
        }

        @Test
        void resolvesWhenNullAndSingleProject() {
            var project = makeProject("only-one", "Only One");
            when(projectRepository.count()).thenReturn(1L);
            when(projectRepository.findAll()).thenReturn(List.of(project));

            var result = service.resolveProject(null);
            assertThat(result.getIdentifier()).isEqualTo("only-one");
        }

        @Test
        void throwsWhenNullAndMultipleProjects() {
            when(projectRepository.count()).thenReturn(2L);

            assertThatThrownBy(() -> service.resolveProject(null)).isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class ResolveProjectId {

        @Test
        void returnsProjectId() {
            var project = makeProjectWithId(PROJECT_ID, "ground-control", "Ground Control");
            when(projectRepository.findByIdentifier("ground-control")).thenReturn(Optional.of(project));

            var result = service.resolveProjectId("ground-control");
            assertThat(result).isEqualTo(PROJECT_ID);
        }
    }
}
