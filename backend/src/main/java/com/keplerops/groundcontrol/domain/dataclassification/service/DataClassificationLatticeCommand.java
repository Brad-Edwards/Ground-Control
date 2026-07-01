package com.keplerops.groundcontrol.domain.dataclassification.service;

import java.util.List;

/**
 * Write-path input describing a project's data classification lattice (GC-GRC-006): the label
 * taxonomy plus the authored permitted-flow relation. The service validates lattice soundness,
 * computes the reflexive-transitive closure, and derives the policy version before persisting.
 */
public record DataClassificationLatticeCommand(List<LabelInput> labels, List<FlowInput> permittedFlows) {

    public DataClassificationLatticeCommand {
        labels = labels == null ? List.of() : List.copyOf(labels);
        permittedFlows = permittedFlows == null ? List.of() : List.copyOf(permittedFlows);
    }

    /** A label definition. {@code rank} is an optional display/ordering hint, not authoritative. */
    public record LabelInput(String key, String displayName, String description, Integer rank) {}

    /** An authored permitted-flow edge: data labeled {@code from} may flow to a sink labeled {@code to}. */
    public record FlowInput(String from, String to) {}
}
