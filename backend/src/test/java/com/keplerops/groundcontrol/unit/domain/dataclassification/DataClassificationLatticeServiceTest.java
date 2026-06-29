package com.keplerops.groundcontrol.unit.domain.dataclassification;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationFlowRule;
import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationLabel;
import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationLattice;
import com.keplerops.groundcontrol.domain.dataclassification.repository.DataClassificationFlowRuleRepository;
import com.keplerops.groundcontrol.domain.dataclassification.repository.DataClassificationLabelRepository;
import com.keplerops.groundcontrol.domain.dataclassification.repository.DataClassificationLatticeRepository;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.FlowInput;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.LabelInput;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeService;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
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
 * Persistence-path behavior of the lattice service (GC-GRC-006): default fallback, custom-policy
 * mapping, validated replace, and reset-to-default. Repositories are mocked so the test stays a unit
 * (Testcontainers integration coverage does not contribute to the analyzer's new-code coverage gate).
 */
@ExtendWith(MockitoExtension.class)
class DataClassificationLatticeServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-1111111111aa");
    private static final UUID LATTICE_ID = UUID.fromString("22222222-2222-2222-2222-2222222222bb");

    @Mock
    private DataClassificationLatticeRepository latticeRepository;

    @Mock
    private DataClassificationLabelRepository labelRepository;

    @Mock
    private DataClassificationFlowRuleRepository flowRuleRepository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private DataClassificationLatticeService service;

    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
    }

    @Test
    void resolveActiveDefinitionFallsBackToDefaultWhenNoCustomRow() {
        when(latticeRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        var def = service.resolveActiveDefinition(PROJECT_ID);

        assertThat(def.source()).isEqualTo(DataClassificationSource.DEFAULT);
        assertThat(def.labelKeys()).hasSize(7);
    }

    @Test
    void resolveActiveDefinitionMapsStoredCustomPolicy() {
        var root = new DataClassificationLattice(project, "dcl/custom", DataClassificationSource.CUSTOM, 2, 3);
        setField(root, "id", LATTICE_ID);
        when(latticeRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(root));
        when(labelRepository.findByLatticeIdOrderByLabelKey(LATTICE_ID))
                .thenReturn(List.of(
                        new DataClassificationLabel(project, root, "A", "Alpha", null, 0),
                        new DataClassificationLabel(project, root, "B", "Bravo", null, 1)));
        when(flowRuleRepository.findByLatticeIdOrderByFromLabelKeyAscToLabelKeyAsc(LATTICE_ID))
                .thenReturn(List.of(
                        new DataClassificationFlowRule(project, root, "A", "A"),
                        new DataClassificationFlowRule(project, root, "A", "B"),
                        new DataClassificationFlowRule(project, root, "B", "B")));

        var def = service.resolveActiveDefinition(PROJECT_ID);

        assertThat(def.source()).isEqualTo(DataClassificationSource.CUSTOM);
        assertThat(def.policyVersion()).isEqualTo("dcl/custom");
        assertThat(def.labelKeys()).containsExactlyInAnyOrder("A", "B");
        assertThat(def.permits("A", "B")).isTrue();
        assertThat(def.permits("B", "A")).isFalse();
    }

    @Test
    void replacePersistsValidatedCustomPolicyWithItsClosure() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(latticeRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(flowRuleRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(labelRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(latticeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var command = new DataClassificationLatticeCommand(
                List.of(new LabelInput("A", "Alpha", null, null), new LabelInput("B", "Bravo", null, null)),
                List.of(new FlowInput("A", "B")));

        var def = service.replace(PROJECT_ID, command);

        assertThat(def.source()).isEqualTo(DataClassificationSource.CUSTOM);
        assertThat(def.labelKeys()).containsExactlyInAnyOrder("A", "B");
        verify(latticeRepository).save(any(DataClassificationLattice.class));
        verify(labelRepository, times(2)).save(any(DataClassificationLabel.class));
        // Reflexive A->A and B->B plus the authored A->B is the persisted closure: three edges.
        verify(flowRuleRepository, times(3)).save(any(DataClassificationFlowRule.class));
    }

    @Test
    void resetToDefaultRemovesCustomPolicyWhenPresent() {
        var root = new DataClassificationLattice(project, "dcl/custom", DataClassificationSource.CUSTOM, 2, 3);
        setField(root, "id", LATTICE_ID);
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(latticeRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(root));
        when(flowRuleRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(labelRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());

        var def = service.resetToDefault(PROJECT_ID);

        assertThat(def.source()).isEqualTo(DataClassificationSource.DEFAULT);
        verify(latticeRepository).delete(root);
    }

    @Test
    void resetToDefaultIsANoOpWhenNoCustomPolicy() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(latticeRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(flowRuleRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(labelRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());

        var def = service.resetToDefault(PROJECT_ID);

        assertThat(def.source()).isEqualTo(DataClassificationSource.DEFAULT);
        verify(latticeRepository, never()).delete(any());
    }
}
