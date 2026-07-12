package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.controls.service.ControlService;
import com.keplerops.groundcontrol.domain.controls.service.ControlTestService;
import com.keplerops.groundcontrol.domain.controls.service.CreateControlTestCommand;
import com.keplerops.groundcontrol.domain.controls.service.UpdateControlTestCommand;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestMethodology;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.LocalDate;
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

@ExtendWith(MockitoExtension.class)
class ControlTestServiceTest {

    @Mock
    private ControlTestRepository controlTestRepository;

    @Mock
    private ControlService controlService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private ControlTestService service;

    private Project project;
    private UUID projectId;
    private Control control;
    private UUID controlId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        controlId = UUID.randomUUID();
        setField(control, "id", controlId);
    }

    private CreateControlTestCommand happyCommand() {
        return new CreateControlTestCommand(
                projectId,
                controlId,
                "CT-001",
                ControlTestMethodology.INSPECTION,
                "Inspect access logs for unauthorized attempts.",
                "No unauthorized attempts observed.",
                "0 unauthorized attempts in sample.",
                ControlTestConclusion.EFFECTIVE,
                "auditor@example.com",
                LocalDate.of(2026, 5, 1),
                "Q2 sample");
    }

    @Nested
    class Create {

        @Test
        void createsControlTest() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(controlTestRepository.existsByProjectIdAndUid(projectId, "CT-001"))
                    .thenReturn(false);
            when(controlTestRepository.save(any(ControlTest.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.create(happyCommand());

            assertThat(result.getUid()).isEqualTo("CT-001");
            assertThat(result.getMethodology()).isEqualTo(ControlTestMethodology.INSPECTION);
            assertThat(result.getConclusion()).isEqualTo(ControlTestConclusion.EFFECTIVE);
            assertThat(result.getControl()).isSameAs(control);
            assertThat(result.getProject()).isSameAs(project);
            assertThat(result.getNotes()).isEqualTo("Q2 sample");
        }

        @Test
        void rejectsDuplicateUidInProject() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(controlTestRepository.existsByProjectIdAndUid(projectId, "CT-001"))
                    .thenReturn(true);

            var command = happyCommand();
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("CT-001");
        }

        @Test
        void rejectsWhenControlNotInProject() {
            // ControlService.getById is the project-scoped gate; if the control belongs to
            // another project (or doesn't exist), it throws NotFoundException and we never
            // touch the repository.
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlService.getById(projectId, controlId))
                    .thenThrow(new NotFoundException("Control not found: " + controlId));

            var command = happyCommand();
            assertThatThrownBy(() -> service.create(command)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Update {

        @Test
        void updatesProvidedFieldsOnly() {
            var existing = existingControlTest();
            when(controlTestRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(controlTestRepository.save(any(ControlTest.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateControlTestCommand(
                    null, // methodology unchanged
                    null,
                    null,
                    "5 unauthorized attempts observed.",
                    ControlTestConclusion.INEFFECTIVE,
                    null,
                    null,
                    "Re-tested after incident");
            var result = service.update(projectId, existing.getId(), command);

            assertThat(result.getMethodology()).isEqualTo(ControlTestMethodology.INSPECTION);
            assertThat(result.getActualResults()).isEqualTo("5 unauthorized attempts observed.");
            assertThat(result.getConclusion()).isEqualTo(ControlTestConclusion.INEFFECTIVE);
            assertThat(result.getNotes()).isEqualTo("Re-tested after incident");
        }

        @Test
        void notFoundWhenWrongProject() {
            var id = UUID.randomUUID();
            when(controlTestRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());
            var command = new UpdateControlTestCommand(null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.update(projectId, id, command)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Reads {

        @Test
        void getByIdReturnsControlTest() {
            var existing = existingControlTest();
            when(controlTestRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));

            var result = service.getById(projectId, existing.getId());

            assertThat(result).isSameAs(existing);
        }

        @Test
        void getByIdNotFound() {
            var id = UUID.randomUUID();
            when(controlTestRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(projectId, id)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void listByProjectDelegates() {
            var existing = existingControlTest();
            when(controlTestRepository.findByProjectIdOrderByTestDateDesc(projectId))
                    .thenReturn(List.of(existing));

            assertThat(service.listByProject(projectId)).containsExactly(existing);
        }

        @Test
        void listByProjectAndControlValidatesControl() {
            var existing = existingControlTest();
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(controlTestRepository.findByProjectIdAndControlIdOrderByTestDateDesc(projectId, controlId))
                    .thenReturn(List.of(existing));

            assertThat(service.listByProjectAndControl(projectId, controlId)).containsExactly(existing);
        }

        @Test
        void listByProjectAndControlRejectsCrossProjectControl() {
            when(controlService.getById(projectId, controlId)).thenThrow(new NotFoundException("Control not found"));

            assertThatThrownBy(() -> service.listByProjectAndControl(projectId, controlId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Test
    void deleteRemovesAndLogs() {
        var existing = existingControlTest();
        when(controlTestRepository.findByIdAndProjectId(existing.getId(), projectId))
                .thenReturn(Optional.of(existing));

        service.delete(projectId, existing.getId());

        verify(controlTestRepository).delete(existing);
    }

    private ControlTest existingControlTest() {
        var ct = new ControlTest(
                project,
                control,
                "CT-001",
                ControlTestMethodology.INSPECTION,
                ControlTestConclusion.EFFECTIVE,
                "auditor@example.com",
                LocalDate.of(2026, 5, 1));
        ct.setTestSteps("Inspect access logs.");
        ct.setExpectedResults("No unauthorized.");
        ct.setActualResults("Observed none.");
        setField(ct, "id", UUID.randomUUID());
        return ct;
    }
}
