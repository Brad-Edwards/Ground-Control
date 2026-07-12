package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.service.ControlService;
import com.keplerops.groundcontrol.domain.controls.service.CreateControlCommand;
import com.keplerops.groundcontrol.domain.controls.service.UpdateControlCommand;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
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
class ControlServiceTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.controls.repository.ControlLinkRepository controlLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository controlTestRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository findingLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository auditLinkRepository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private ControlService controlService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private Control makeControl() {
        var control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        control.setDescription("Network access control");
        control.setOwner("Security Team");
        setField(control, "id", UUID.randomUUID());
        return control;
    }

    @Nested
    class Create {

        @Test
        void createsControl() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.existsByProjectIdAndUid(projectId, "CTRL-001"))
                    .thenReturn(false);
            when(controlRepository.save(any(Control.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateControlCommand(
                    projectId,
                    "CTRL-001",
                    "Access Control",
                    ControlFunction.PREVENTIVE,
                    "desc",
                    "obj",
                    "owner",
                    "scope",
                    null,
                    null,
                    "Access Control",
                    "ISO 27001");
            var result = controlService.create(command);

            assertThat(result.getUid()).isEqualTo("CTRL-001");
            assertThat(result.getTitle()).isEqualTo("Access Control");
            assertThat(result.getControlFunction()).isEqualTo(ControlFunction.PREVENTIVE);
        }

        @Test
        void throwsOnDuplicateUid() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.existsByProjectIdAndUid(projectId, "CTRL-001"))
                    .thenReturn(true);

            var command = new CreateControlCommand(
                    projectId,
                    "CTRL-001",
                    "title",
                    ControlFunction.DETECTIVE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> controlService.create(command)).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class Update {

        @Test
        void updatesControl() {
            var control = makeControl();
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));
            when(controlRepository.save(any(Control.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateControlCommand(
                    "Updated Title", ControlFunction.DETECTIVE, null, null, null, null, null, null, null, null);
            var result = controlService.update(projectId, control.getId(), command);

            assertThat(result.getTitle()).isEqualTo("Updated Title");
            assertThat(result.getControlFunction()).isEqualTo(ControlFunction.DETECTIVE);
            assertThat(result.getDescription()).isEqualTo("Network access control");
        }
    }

    // -------------------------------------------------------------------------
    // ADR-089: the GC-GRC-011 implementation-evidence precondition on
    // transitionStatus is retired. The ordinary ControlStatus transition graph
    // is authoritative again — a shape-valid hop succeeds with no dependency on
    // ControlLink/ControlTest evidence, and an impossible hop still fails with
    // the structural invalid_status_transition error.
    // -------------------------------------------------------------------------

    @Nested
    class TransitionStatus {

        @Test
        void transitionsDraftToProposed() {
            var control = makeControl();
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));
            when(controlRepository.save(any(Control.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = controlService.transitionStatus(projectId, control.getId(), ControlStatus.PROPOSED);

            assertThat(result.getStatus()).isEqualTo(ControlStatus.PROPOSED);
        }

        @Test
        void transitionsToImplementedWithNoEvidenceRequired() {
            // Previously blocked by GC-GRC-011 without a CODE/IMPLEMENTS link and an
            // efficacy test. That precondition is retired (ADR-089); the ordinary
            // shape-valid PROPOSED -> IMPLEMENTED hop now succeeds with no evidence
            // repository interaction at all.
            var control = makeControl();
            setField(control, "status", ControlStatus.PROPOSED);
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));
            when(controlRepository.save(any(Control.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = controlService.transitionStatus(projectId, control.getId(), ControlStatus.IMPLEMENTED);

            assertThat(result.getStatus()).isEqualTo(ControlStatus.IMPLEMENTED);
            verifyNoInteractions(controlLinkRepository, controlTestRepository);
        }

        @Test
        void invalidShapeStillThrowsShapeError() {
            // A transition from DRAFT straight to IMPLEMENTED is structurally invalid
            // regardless of the retired evidence gate; the ordinary transition graph
            // still rejects it.
            var control = makeControl(); // starts in DRAFT
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));

            var controlId = control.getId();
            assertThatThrownBy(() -> controlService.transitionStatus(projectId, controlId, ControlStatus.IMPLEMENTED))
                    .isInstanceOf(com.keplerops.groundcontrol.domain.exception.DomainValidationException.class)
                    .extracting("errorCode")
                    .isEqualTo("invalid_status_transition");
            verifyNoInteractions(controlLinkRepository, controlTestRepository);
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsControl() {
            var control = makeControl();
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));

            var result = controlService.getById(projectId, control.getId());

            assertThat(result.getUid()).isEqualTo("CTRL-001");
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(controlRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controlService.getById(projectId, id)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ListByProject {

        @Test
        void listsControls() {
            when(controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(makeControl()));

            var result = controlService.listByProject(projectId);

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesControl() {
            var control = makeControl();
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.CONTROL,
                            control.getId(),
                            projectId))
                    .thenReturn(java.util.List.of());
            when(controlTestRepository.countByProjectIdAndControlId(projectId, control.getId()))
                    .thenReturn(0L);
            when(controlLinkRepository.findByControlId(control.getId())).thenReturn(java.util.List.of());

            controlService.delete(projectId, control.getId());

            verify(controlRepository).delete(control);
        }

        @Test
        void deletesOutboundLinksThroughRepositoryBeforeParent() {
            var control = makeControl();
            var outboundLinks = java.util.List.of(new com.keplerops.groundcontrol.domain.controls.model.ControlLink(
                    control,
                    com.keplerops.groundcontrol.domain.controls.state.ControlLinkTargetType.ASSET,
                    UUID.randomUUID(),
                    null,
                    com.keplerops.groundcontrol.domain.controls.state.ControlLinkType.PROTECTS));
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.CONTROL,
                            control.getId(),
                            projectId))
                    .thenReturn(java.util.List.of());
            when(controlTestRepository.countByProjectIdAndControlId(projectId, control.getId()))
                    .thenReturn(0L);
            when(controlLinkRepository.findByControlId(control.getId())).thenReturn(outboundLinks);

            controlService.delete(projectId, control.getId());

            // Envers writes delete revisions only when Hibernate sees the link
            // delete. Driving outbound link deletes through the repository before
            // deleting the parent closes the parent-delete audit-history gap
            // (cycle-2 pre-push codex review on issue #279).
            var inOrder = inOrder(controlLinkRepository, controlRepository);
            inOrder.verify(controlLinkRepository).deleteAll(outboundLinks);
            inOrder.verify(controlRepository).delete(control);
        }

        @Test
        void rejectsDeleteWhenInboundAuditLinkReferencesControl() {
            var control = makeControl();
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.CONTROL,
                            control.getId(),
                            projectId))
                    .thenReturn(java.util.List.of());
            when(auditLinkRepository.findAuditUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType.CONTROL,
                            control.getId(),
                            projectId))
                    .thenReturn(java.util.List.of("AUDIT-001"));

            var controlId = control.getId();
            var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    com.keplerops.groundcontrol.domain.exception.ConflictException.class,
                    () -> controlService.delete(projectId, controlId));
            assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("AuditLink references exist")
                    .extracting("errorCode")
                    .isEqualTo("control_referenced");
            assertThat(thrown.getDetail()).containsEntry("auditCount", 1);
            verifyNoInteractions(controlLinkRepository);
            verify(controlRepository, never()).delete(control);
        }

        @Test
        void rejectsDeleteWhenInboundFindingLinkReferencesControl() {
            var control = makeControl();
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.CONTROL,
                            control.getId(),
                            projectId))
                    .thenReturn(java.util.List.of("FIND-001"));

            // FindingLink.targetEntityId is not an FK, so without this guard the
            // delete would leave dangling FindingLink rows (cycle-3 pre-push codex
            // review on issue #279, ADR-038).
            var controlId = control.getId();
            var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    com.keplerops.groundcontrol.domain.exception.ConflictException.class,
                    () -> controlService.delete(projectId, controlId));
            assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("FindingLink references exist")
                    .extracting("errorCode")
                    .isEqualTo("control_referenced");
            assertThat(thrown.getDetail())
                    .containsEntry("findingCount", 1)
                    .containsEntry("findingUids", (java.io.Serializable) java.util.List.of("FIND-001"));
            // Parent + outbound-link cleanup must be skipped when the guard fires.
            verifyNoInteractions(controlLinkRepository);
            verify(controlRepository, never()).delete(control);
        }

        @Test
        void rejectsWhenDependentControlTestsExist() {
            // ADR-039: ControlTest rows are audited evidence; cascading them on parent delete
            // would destroy provenance silently, and a raw FK violation would surface as 500.
            // The service uses count-only existence checks (no full-row hydration) and returns
            // a clean 409 with the dependent count so the caller can act.
            var control = makeControl();
            when(controlRepository.findByIdAndProjectId(control.getId(), projectId))
                    .thenReturn(Optional.of(control));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.CONTROL,
                            control.getId(),
                            projectId))
                    .thenReturn(java.util.List.of());
            when(controlTestRepository.countByProjectIdAndControlId(projectId, control.getId()))
                    .thenReturn(3L);

            assertThatThrownBy(() -> controlService.delete(projectId, control.getId()))
                    .isInstanceOf(com.keplerops.groundcontrol.domain.exception.ConflictException.class)
                    .hasMessageContaining("dependent audit evidence");
        }
    }
}
