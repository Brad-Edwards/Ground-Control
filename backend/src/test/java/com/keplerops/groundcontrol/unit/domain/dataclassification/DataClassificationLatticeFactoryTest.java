package com.keplerops.groundcontrol.unit.domain.dataclassification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.FlowInput;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.LabelInput;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeDefinition;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeFactory;
import com.keplerops.groundcontrol.domain.dataclassification.service.DefaultDataClassificationLattice;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validation, transitive closure, and policy-version behavior of the lattice factory (GC-GRC-006). */
class DataClassificationLatticeFactoryTest {

    private static DataClassificationLatticeCommand command(List<LabelInput> labels, List<FlowInput> flows) {
        return new DataClassificationLatticeCommand(labels, flows);
    }

    private static LabelInput label(String key) {
        return new LabelInput(key, key, null, null);
    }

    @Test
    void defaultLatticeShipsAsASoundInformationFlowLattice() {
        DataClassificationLatticeDefinition def = DefaultDataClassificationLattice.definition();

        assertThat(def.source()).isEqualTo(DataClassificationSource.DEFAULT);
        assertThat(def.labelKeys())
                .containsExactlyInAnyOrder(
                        "PUBLIC", "INTERNAL", "CONFIDENTIAL", "PII", "CREDENTIALS", "SECRETS", "REGULATED");
        // Up-flow to equal-or-more-protected sinks is permitted; down-flow leaks.
        assertThat(def.permits("PUBLIC", "PII")).isTrue();
        assertThat(def.permits("CONFIDENTIAL", "REGULATED")).isTrue();
        assertThat(def.permits("PII", "PII")).isTrue();
        assertThat(def.permits("PII", "PUBLIC")).isFalse();
        // The four most-sensitive labels are mutually incomparable.
        assertThat(def.permits("PII", "SECRETS")).isFalse();
        assertThat(def.permits("SECRETS", "PII")).isFalse();
    }

    @Test
    void transitiveClosureMakesIndirectFlowsPermitted() {
        var def = DataClassificationLatticeFactory.build(
                DataClassificationSource.CUSTOM,
                command(
                        List.of(label("A"), label("B"), label("C")),
                        List.of(new FlowInput("A", "B"), new FlowInput("B", "C"))));

        assertThat(def.permits("A", "C")).isTrue();
        assertThat(def.permits("A", "A")).isTrue(); // reflexive
        assertThat(def.permits("C", "A")).isFalse();
    }

    @Test
    void emptyLabelSetIsRejected() {
        var command = command(List.of(), List.of());
        assertThatThrownBy(() -> DataClassificationLatticeFactory.build(DataClassificationSource.CUSTOM, command))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void duplicateLabelKeyIsRejected() {
        var command = command(List.of(label("A"), label("A")), List.of());
        assertThatThrownBy(() -> DataClassificationLatticeFactory.build(DataClassificationSource.CUSTOM, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void invalidLabelKeySyntaxIsRejected() {
        var command = command(List.of(label("bad key!")), List.of());
        assertThatThrownBy(() -> DataClassificationLatticeFactory.build(DataClassificationSource.CUSTOM, command))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void danglingFlowEdgeIsRejected() {
        var command = command(List.of(label("A")), List.of(new FlowInput("A", "GHOST")));
        assertThatThrownBy(() -> DataClassificationLatticeFactory.build(DataClassificationSource.CUSTOM, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void cycleBetweenDistinctLabelsBreaksAntisymmetryAndIsRejected() {
        var command =
                command(List.of(label("A"), label("B")), List.of(new FlowInput("A", "B"), new FlowInput("B", "A")));
        assertThatThrownBy(() -> DataClassificationLatticeFactory.build(DataClassificationSource.CUSTOM, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("antisymmetric");
    }

    @Test
    void policyVersionIsDeterministicAndContentSensitive() {
        var labels = List.of(label("A"), label("B"));
        var flows = List.of(new FlowInput("A", "B"));
        var first = DataClassificationLatticeFactory.build(DataClassificationSource.CUSTOM, command(labels, flows));
        var same = DataClassificationLatticeFactory.build(DataClassificationSource.CUSTOM, command(labels, flows));
        var different =
                DataClassificationLatticeFactory.build(DataClassificationSource.CUSTOM, command(labels, List.of()));

        assertThat(first.policyVersion()).isEqualTo(same.policyVersion());
        assertThat(first.policyVersion()).isNotEqualTo(different.policyVersion());
        assertThat(first.policyVersion()).startsWith("dcl/");
    }
}
