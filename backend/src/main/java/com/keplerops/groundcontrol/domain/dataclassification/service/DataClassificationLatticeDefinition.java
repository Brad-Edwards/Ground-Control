package com.keplerops.groundcontrol.domain.dataclassification.service;

import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical in-memory representation of a project's active data classification lattice
 * (GC-GRC-006). Produced from either the shipped default or a persisted custom lattice, and consumed
 * by evaluation. {@code permittedFlows} is the reflexive-transitive closure of the authored
 * relation, so {@link #permits(String, String)} is a total, deterministic allow decision.
 */
public record DataClassificationLatticeDefinition(
        DataClassificationSource source, String policyVersion, List<Label> labels, Set<Edge> permittedFlows) {

    public DataClassificationLatticeDefinition {
        labels = List.copyOf(labels);
        permittedFlows = Set.copyOf(permittedFlows);
    }

    /** A label in the lattice. {@code rank} is a display hint, never the authoritative ordering. */
    public record Label(String key, String displayName, String description, Integer rank) {}

    /** A permitted-flow edge in the closure: {@code from} may flow to {@code to}. */
    public record Edge(String from, String to) {}

    /** True iff data labeled {@code from} may flow to a sink labeled {@code to}. */
    public boolean permits(String from, String to) {
        return permittedFlows.contains(new Edge(from, to));
    }

    public Set<String> labelKeys() {
        return labels.stream().map(Label::key).collect(Collectors.toUnmodifiableSet());
    }

    public int labelCount() {
        return labels.size();
    }

    public int edgeCount() {
        return permittedFlows.size();
    }
}
