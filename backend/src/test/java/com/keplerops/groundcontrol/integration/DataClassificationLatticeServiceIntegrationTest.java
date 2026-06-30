package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.FlowInput;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.LabelInput;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeService;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the data classification lattice persistence round-trip (GC-GRC-006 clause d): the default
 * applies when nothing is stored, a custom policy persists and is read back with a fresh policy
 * version, replacing it again swaps the policy cleanly under the one-lattice-per-project constraint,
 * and reset reverts to the default.
 */
@Transactional
class DataClassificationLatticeServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DataClassificationLatticeService latticeService;

    @Autowired
    private ProjectRepository projectRepository;

    private Project project;

    @BeforeEach
    void setUp() {
        project = projectRepository.findByIdentifier("ground-control").orElseThrow();
        // Start from a known clean state regardless of prior tests in the shared container.
        latticeService.resetToDefault(project.getId());
    }

    @Test
    void defaultAppliesWhenNoCustomLatticeIsStored() {
        var def = latticeService.resolveActiveDefinition(project.getId());

        assertThat(def.source()).isEqualTo(DataClassificationSource.DEFAULT);
        assertThat(def.labelKeys()).contains("PII", "PUBLIC", "SECRETS");
        assertThat(def.permits("PII", "PUBLIC")).isFalse();
    }

    @Test
    void customLatticePersistsAndIsReadBack() {
        var command = new DataClassificationLatticeCommand(
                List.of(new LabelInput("LOW", "Low", null, 0), new LabelInput("HIGH", "High", "Sensitive", 1)),
                List.of(new FlowInput("LOW", "HIGH")));

        var written = latticeService.replace(project.getId(), command);
        var readBack = latticeService.resolveActiveDefinition(project.getId());

        assertThat(readBack.source()).isEqualTo(DataClassificationSource.CUSTOM);
        assertThat(readBack.policyVersion()).isEqualTo(written.policyVersion());
        assertThat(readBack.labelKeys()).containsExactlyInAnyOrder("LOW", "HIGH");
        assertThat(readBack.permits("LOW", "HIGH")).isTrue();
        assertThat(readBack.permits("HIGH", "LOW")).isFalse();
    }

    @Test
    void replacingAnExistingCustomLatticeSwapsItCleanly() {
        latticeService.replace(
                project.getId(),
                new DataClassificationLatticeCommand(List.of(new LabelInput("ONLY", "Only", null, 0)), List.of()));
        var first = latticeService.resolveActiveDefinition(project.getId());

        latticeService.replace(
                project.getId(),
                new DataClassificationLatticeCommand(
                        List.of(new LabelInput("A", "A", null, 0), new LabelInput("B", "B", null, 1)),
                        List.of(new FlowInput("A", "B"))));
        var second = latticeService.resolveActiveDefinition(project.getId());

        assertThat(second.labelKeys()).containsExactlyInAnyOrder("A", "B");
        assertThat(second.policyVersion()).isNotEqualTo(first.policyVersion());
    }

    @Test
    void resetToDefaultRemovesTheCustomLattice() {
        latticeService.replace(
                project.getId(),
                new DataClassificationLatticeCommand(List.of(new LabelInput("X", "X", null, 0)), List.of()));
        assertThat(latticeService.resolveActiveDefinition(project.getId()).source())
                .isEqualTo(DataClassificationSource.CUSTOM);

        latticeService.resetToDefault(project.getId());

        assertThat(latticeService.resolveActiveDefinition(project.getId()).source())
                .isEqualTo(DataClassificationSource.DEFAULT);
    }
}
