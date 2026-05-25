package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlLink;
import com.keplerops.groundcontrol.domain.controls.repository.ControlLinkRepository;
import com.keplerops.groundcontrol.domain.controls.service.ControlLinkService;
import com.keplerops.groundcontrol.domain.controls.service.ControlService;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkTargetType;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ControlLinkServiceTest {

    @Mock
    private ControlLinkRepository controlLinkRepository;

    @Mock
    private ControlService controlService;

    @Mock
    private GraphTargetResolverService graphTargetResolverService;

    @InjectMocks
    private ControlLinkService controlLinkService;

    private UUID projectId;
    private UUID controlId;
    private Control control;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        controlId = UUID.randomUUID();
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", projectId);
        control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        setField(control, "id", controlId);
    }

    @Nested
    class Create {

        @Test
        void createsLinkWithExternalIdentifier() {
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(graphTargetResolverService.validateControlTarget(
                            projectId, ControlLinkTargetType.EXTERNAL, null, "EXT-001"))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(null, "EXT-001", false));
            when(controlLinkRepository.existsByControlIdAndTargetTypeAndTargetIdentifierAndLinkType(
                            controlId, ControlLinkTargetType.EXTERNAL, "EXT-001", ControlLinkType.ASSOCIATED))
                    .thenReturn(false);
            when(controlLinkRepository.save(any(ControlLink.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new com.keplerops.groundcontrol.domain.controls.service.CreateControlLinkCommand(
                    ControlLinkTargetType.EXTERNAL, null, "EXT-001", ControlLinkType.ASSOCIATED, null, null);
            var result = controlLinkService.create(projectId, controlId, command);

            assertThat(result.getTargetType()).isEqualTo(ControlLinkTargetType.EXTERNAL);
            assertThat(result.getTargetIdentifier()).isEqualTo("EXT-001");
            assertThat(result.getLinkType()).isEqualTo(ControlLinkType.ASSOCIATED);
        }

        @Test
        void createsLinkWithInternalEntityId() {
            var assetId = UUID.randomUUID();
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(graphTargetResolverService.validateControlTarget(
                            projectId, ControlLinkTargetType.ASSET, assetId, null))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(assetId, null, true));
            when(controlLinkRepository.existsByControlIdAndTargetTypeAndTargetEntityIdAndLinkType(
                            controlId, ControlLinkTargetType.ASSET, assetId, ControlLinkType.PROTECTS))
                    .thenReturn(false);
            when(controlLinkRepository.save(any(ControlLink.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new com.keplerops.groundcontrol.domain.controls.service.CreateControlLinkCommand(
                    ControlLinkTargetType.ASSET, assetId, null, ControlLinkType.PROTECTS, null, null);
            var result = controlLinkService.create(projectId, controlId, command);

            assertThat(result.getTargetType()).isEqualTo(ControlLinkTargetType.ASSET);
            assertThat(result.getTargetEntityId()).isEqualTo(assetId);
            assertThat(result.getTargetIdentifier()).isNull();
            assertThat(result.getLinkType()).isEqualTo(ControlLinkType.PROTECTS);
        }

        // PR #875 security provenance: ControlLinkService used to persist targetEntityId
        // without validating project scope. GraphTargetResolverService now owns the
        // existence/project check for all internal types. At the service layer the
        // resolver is mocked, so "non-existent UUID" and "cross-project UUID" are
        // observationally identical — one parameterized test covers both per type
        // without duplicating a 10× matrix.
        @ParameterizedTest
        @EnumSource(
                value = ControlLinkTargetType.class,
                names = {
                    "ASSET",
                    "REQUIREMENT",
                    "RISK_SCENARIO",
                    "RISK_REGISTER_RECORD",
                    "RISK_ASSESSMENT_RESULT",
                    "TREATMENT_PLAN",
                    "METHODOLOGY_PROFILE",
                    "OBSERVATION",
                    "FINDING",
                    "EVIDENCE"
                })
        void rejectsInternalTargetWhenResolverThrows(ControlLinkTargetType targetType) {
            var targetEntityId = UUID.randomUUID();
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(graphTargetResolverService.validateControlTarget(
                            eq(projectId), eq(targetType), any(UUID.class), isNull()))
                    .thenThrow(new DomainValidationException(
                            targetType.name() + " target not found in the requested project"));

            var command = new com.keplerops.groundcontrol.domain.controls.service.CreateControlLinkCommand(
                    targetType, targetEntityId, null, ControlLinkType.ASSOCIATED, null, null);
            assertThatThrownBy(() -> controlLinkService.create(projectId, controlId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("not found in the requested project");

            verify(controlLinkRepository, never()).save(any());
        }

        @Test
        void rejectsInternalTargetWithMissingEntityId() {
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(graphTargetResolverService.validateControlTarget(
                            projectId, ControlLinkTargetType.REQUIREMENT, null, null))
                    .thenThrow(new DomainValidationException("Requirement links require targetEntityId"));

            var command = new com.keplerops.groundcontrol.domain.controls.service.CreateControlLinkCommand(
                    ControlLinkTargetType.REQUIREMENT, null, null, ControlLinkType.MITIGATES, null, null);
            assertThatThrownBy(() -> controlLinkService.create(projectId, controlId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("require targetEntityId");
        }

        @Test
        void rejectsExternalTargetWithBlankIdentifier() {
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(graphTargetResolverService.validateControlTarget(
                            projectId, ControlLinkTargetType.EXTERNAL, null, null))
                    .thenThrow(new DomainValidationException("External or unmodeled links require targetIdentifier"));

            var command = new com.keplerops.groundcontrol.domain.controls.service.CreateControlLinkCommand(
                    ControlLinkTargetType.EXTERNAL, null, null, ControlLinkType.ASSOCIATED, null, null);
            assertThatThrownBy(() -> controlLinkService.create(projectId, controlId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("require targetIdentifier");
        }

        @Test
        void throwsOnDuplicateLink() {
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(graphTargetResolverService.validateControlTarget(
                            projectId, ControlLinkTargetType.EXTERNAL, null, "ext-1"))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(null, "ext-1", false));
            when(controlLinkRepository.existsByControlIdAndTargetTypeAndTargetIdentifierAndLinkType(
                            controlId, ControlLinkTargetType.EXTERNAL, "ext-1", ControlLinkType.ASSOCIATED))
                    .thenReturn(true);

            var command = new com.keplerops.groundcontrol.domain.controls.service.CreateControlLinkCommand(
                    ControlLinkTargetType.EXTERNAL, null, "ext-1", ControlLinkType.ASSOCIATED, null, null);
            assertThatThrownBy(() -> controlLinkService.create(projectId, controlId, command))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class ListByControl {

        @Test
        void listsAllLinks() {
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            var link = new ControlLink(control, ControlLinkTargetType.ASSET, null, "A-1", ControlLinkType.PROTECTS);
            when(controlLinkRepository.findByControlId(controlId)).thenReturn(List.of(link));

            var result = controlLinkService.listByControl(projectId, controlId, null);

            assertThat(result).hasSize(1);
        }

        @Test
        void filtersLinksByTargetType() {
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            when(controlLinkRepository.findByControlIdAndTargetType(controlId, ControlLinkTargetType.EVIDENCE))
                    .thenReturn(List.of());

            var result = controlLinkService.listByControl(projectId, controlId, ControlLinkTargetType.EVIDENCE);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesLink() {
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            var linkId = UUID.randomUUID();
            var link = new ControlLink(control, ControlLinkTargetType.ASSET, null, "A-1", ControlLinkType.PROTECTS);
            setField(link, "id", linkId);
            when(controlLinkRepository.findByIdAndControlProjectId(linkId, projectId))
                    .thenReturn(Optional.of(link));

            controlLinkService.delete(projectId, controlId, linkId);

            verify(controlLinkRepository).delete(link);
        }

        @Test
        void throwsWhenLinkNotFound() {
            when(controlService.getById(projectId, controlId)).thenReturn(control);
            var linkId = UUID.randomUUID();
            when(controlLinkRepository.findByIdAndControlProjectId(linkId, projectId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> controlLinkService.delete(projectId, controlId, linkId))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
