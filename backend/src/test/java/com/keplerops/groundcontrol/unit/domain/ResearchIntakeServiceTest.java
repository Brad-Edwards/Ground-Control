package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContributionType;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.model.ResearchIntake;
import com.keplerops.groundcontrol.domain.research.repository.ResearchIntakeRepository;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeService;
import java.util.ArrayList;
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
class ResearchIntakeServiceTest {

    @Mock
    private ResearchIntakeRepository intakeRepository;

    private ResearchIntakeService service;

    @BeforeEach
    void setUp() {
        service = new ResearchIntakeService(intakeRepository);
    }

    private Project makeProject(ProjectType type) {
        var project = new Project("research-p", "Research Project", type);
        TestUtil.setField(project, "id", UUID.randomUUID());
        return project;
    }

    /**
     * Build a valid command. validate() iterates raw allowed-tool entries
     * before normaliseTools() runs, so the input list cannot contain blank
     * entries; the with-spaces and duplicate entries exercise the trim +
     * dedup paths in normaliseTools().
     */
    private ResearchIntakeCommand validCommand() {
        return new ResearchIntakeCommand(
                "  Investigate research goal  ",
                "  Paper context  ",
                ContributionType.REVIEW,
                IntendedOutput.SCOPING_REVIEW,
                AutonomyLevel.COPILOT,
                List.of("cite_resolve", "  zotero_search  ", "cite_resolve"),
                "  Privacy note  ",
                1000L,
                60,
                500_000L);
    }

    @Nested
    class Create {

        @Test
        void happyPath_persistsTrimmedNormalisedIntake() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            when(intakeRepository.save(any(ResearchIntake.class))).thenAnswer(inv -> inv.getArgument(0));

            var saved = service.create(project, validCommand());

            assertThat(saved.getGoal()).isEqualTo("Investigate research goal");
            assertThat(saved.getPaperContext()).isEqualTo("Paper context");
            assertThat(saved.getPrivacyConstraints()).isEqualTo("Privacy note");
            assertThat(saved.getAllowedTools()).containsExactly("cite_resolve", "zotero_search");
            assertThat(saved.getBudgetTokens()).isEqualTo(1000L);
            assertThat(saved.getContributionType()).isEqualTo(ContributionType.REVIEW);
            assertThat(saved.getIntendedOutput()).isEqualTo(IntendedOutput.SCOPING_REVIEW);
            assertThat(saved.getAutonomyLevel()).isEqualTo(AutonomyLevel.COPILOT);
        }

        @Test
        void nonResearchProject_throwsValidation() {
            var project = makeProject(ProjectType.SOFTWARE);

            assertThatThrownBy(() -> service.create(project, validCommand()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("RESEARCH");
        }

        @Test
        void existingIntake_throwsConflict() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.create(project, validCommand())).isInstanceOf(ConflictException.class);
        }

        @Test
        void nullCommand_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);

            assertThatThrownBy(() -> service.create(project, null)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void blankGoal_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            var bad = new ResearchIntakeCommand(
                    "  ",
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    List.of(),
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("goal");
        }

        @Test
        void nullContributionType_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            var bad = new ResearchIntakeCommand(
                    "goal",
                    null,
                    null,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    List.of(),
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("contributionType");
        }

        @Test
        void nullIntendedOutput_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            var bad = new ResearchIntakeCommand(
                    "goal",
                    null,
                    ContributionType.REVIEW,
                    null,
                    AutonomyLevel.COPILOT,
                    List.of(),
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("intendedOutput");
        }

        @Test
        void nullAutonomyLevel_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            var bad = new ResearchIntakeCommand(
                    "goal",
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("autonomyLevel");
        }

        @Test
        void nullAllowedTools_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            var bad = new ResearchIntakeCommand(
                    "goal",
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    null,
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("allowedTools");
        }

        @Test
        void blankAllowedToolEntry_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            // Pre-trimmed-empty entries are stripped by normaliseTools; raw null/empty after
            // normaliseTools won't trigger this. Use a list that bypasses normalisation: the
            // validate() function runs on the command-as-passed before normalisation.
            var tools = new ArrayList<String>();
            tools.add("valid");
            tools.add(null);
            var bad = new ResearchIntakeCommand(
                    "goal",
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    tools,
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("allowedTools");
        }

        @Test
        void tooManyAllowedTools_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            var tools = new ArrayList<String>();
            for (int i = 0; i < 101; i++) {
                tools.add("t" + i);
            }
            var bad = new ResearchIntakeCommand(
                    "goal",
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    tools,
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("allowedTools");
        }

        @Test
        void overlongAllowedToolEntry_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            var bad = new ResearchIntakeCommand(
                    "goal",
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    List.of("x".repeat(101)),
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("allowedTools");
        }

        @Test
        void negativeBudget_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            var bad = new ResearchIntakeCommand(
                    "goal",
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    List.of(),
                    null,
                    -1L,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("budgetTokens");
        }

        @Test
        void overlongGoal_throwsValidation() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.existsByProjectId(project.getId())).thenReturn(false);
            var bad = new ResearchIntakeCommand(
                    "x".repeat(4001),
                    null,
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    List.of(),
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(project, bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("goal");
        }
    }

    @Nested
    class Replace {

        @Test
        void happyPath_updatesFields() {
            var project = makeProject(ProjectType.RESEARCH);
            var existing = new ResearchIntake(
                    project,
                    "old",
                    ContributionType.OTHER,
                    IntendedOutput.OTHER,
                    AutonomyLevel.AUTONOMOUS,
                    List.of("old-tool"));
            TestUtil.setField(existing, "id", UUID.randomUUID());
            when(intakeRepository.findByProjectId(project.getId())).thenReturn(Optional.of(existing));
            when(intakeRepository.save(any(ResearchIntake.class))).thenAnswer(inv -> inv.getArgument(0));

            var updated = service.replace(project, validCommand());

            assertThat(updated.getGoal()).isEqualTo("Investigate research goal");
            assertThat(updated.getContributionType()).isEqualTo(ContributionType.REVIEW);
            assertThat(updated.getAllowedTools()).containsExactly("cite_resolve", "zotero_search");
        }

        @Test
        void nonResearchProject_throwsValidation() {
            var project = makeProject(ProjectType.GRC);

            assertThatThrownBy(() -> service.replace(project, validCommand()))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void noExistingIntake_throwsNotFound() {
            var project = makeProject(ProjectType.RESEARCH);
            when(intakeRepository.findByProjectId(project.getId())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.replace(project, validCommand())).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class FindByProject {

        @Test
        void returnsIntakeWhenPresent() {
            var project = makeProject(ProjectType.RESEARCH);
            var intake = new ResearchIntake(
                    project,
                    "g",
                    ContributionType.REVIEW,
                    IntendedOutput.SCOPING_REVIEW,
                    AutonomyLevel.COPILOT,
                    List.of());
            when(intakeRepository.findByProjectId(project.getId())).thenReturn(Optional.of(intake));

            var found = service.findByProject(project);

            assertThat(found).contains(intake);
        }

        @Test
        void returnsEmptyWhenAbsent() {
            var project = makeProject(ProjectType.SOFTWARE);
            when(intakeRepository.findByProjectId(project.getId())).thenReturn(Optional.empty());

            var found = service.findByProject(project);

            assertThat(found).isEmpty();
        }
    }
}
