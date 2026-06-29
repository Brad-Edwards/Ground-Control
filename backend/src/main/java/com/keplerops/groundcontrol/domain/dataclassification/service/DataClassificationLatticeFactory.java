package com.keplerops.groundcontrol.domain.dataclassification.service;

import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.FlowInput;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeDefinition.Edge;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeDefinition.Label;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validation + closure logic for a data classification lattice (GC-GRC-006). Shared by the
 * persistence service and the default-lattice provider so the soundness rules and policy-version
 * digest are defined exactly once. Validates that the authored relation forms a sound
 * information-flow lattice and materialises its reflexive-transitive closure so the allow decision
 * is total and deterministic for every (source, sink) pair.
 */
public final class DataClassificationLatticeFactory {

    static final Pattern LABEL_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$");

    private DataClassificationLatticeFactory() {
        // utility
    }

    /**
     * Validate {@code command}, compute the reflexive-transitive closure of its permitted-flow
     * relation, and derive a deterministic policy version. Throws {@link DomainValidationException}
     * when the taxonomy or relation is unsound (bad key, duplicate, dangling edge, or a cycle
     * between distinct labels that would violate antisymmetry).
     */
    public static DataClassificationLatticeDefinition build(
            DataClassificationSource source, DataClassificationLatticeCommand command) {
        if (command.labels().isEmpty()) {
            throw validation("Lattice must define at least one label", "labels");
        }
        var labelByKey = new LinkedHashMap<String, Label>();
        for (var input : command.labels()) {
            var key = trim(input.key());
            if (key == null || !LABEL_KEY.matcher(key).matches()) {
                throw validation("Label key must match " + LABEL_KEY.pattern(), "labels.key");
            }
            if (labelByKey.containsKey(key)) {
                throw validation("Duplicate label key: " + key, "labels.key");
            }
            var displayName = trim(input.displayName());
            if (displayName == null) {
                throw validation("Label displayName is required for " + key, "labels.displayName");
            }
            labelByKey.put(key, new Label(key, displayName, trim(input.description()), input.rank()));
        }

        var keys = List.copyOf(labelByKey.keySet());
        var reachable = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var key : keys) {
            var targets = new LinkedHashSet<String>();
            targets.add(key); // reflexive: data always flows to a same-labeled sink
            reachable.put(key, targets);
        }
        for (FlowInput flow : command.permittedFlows()) {
            var from = trim(flow.from());
            var to = trim(flow.to());
            if (from == null || !labelByKey.containsKey(from)) {
                throw validation("Permitted flow references unknown source label: " + from, "permittedFlows.from");
            }
            if (to == null || !labelByKey.containsKey(to)) {
                throw validation("Permitted flow references unknown sink label: " + to, "permittedFlows.to");
            }
            reachable.get(from).add(to);
        }

        // Warshall transitive closure over the label set.
        for (var k : keys) {
            for (var i : keys) {
                if (reachable.get(i).contains(k)) {
                    reachable.get(i).addAll(reachable.get(k));
                }
            }
        }

        // Antisymmetry: a permitted flow both ways between distinct labels means they are the same
        // security level — an ambiguous, self-contradictory lattice. Reject it.
        for (var a : keys) {
            for (var b : keys) {
                if (!a.equals(b)
                        && reachable.get(a).contains(b)
                        && reachable.get(b).contains(a)) {
                    throw validation(
                            "Permitted-flow relation is not antisymmetric between labels " + a + " and " + b,
                            "permittedFlows",
                            "lattice_not_antisymmetric");
                }
            }
        }

        var closure = new LinkedHashSet<Edge>();
        for (var from : keys) {
            for (var to : reachable.get(from)) {
                closure.add(new Edge(from, to));
            }
        }
        var labels = keys.stream().map(labelByKey::get).toList();
        return new DataClassificationLatticeDefinition(source, policyVersion(labels, closure), labels, closure);
    }

    /** Deterministic content digest over the labels and the closed permitted-flow relation. */
    static String policyVersion(List<Label> labels, Set<Edge> closure) {
        var sb = new StringBuilder();
        labels.stream().sorted(Comparator.comparing(Label::key)).forEach(label -> sb.append("L|")
                .append(label.key())
                .append('|')
                .append(label.displayName())
                .append('|')
                .append(label.description() == null ? "" : label.description())
                .append('|')
                .append(label.rank() == null ? "" : label.rank())
                .append('\n'));
        closure.stream()
                .sorted(Comparator.comparing(Edge::from).thenComparing(Edge::to))
                .forEach(edge -> sb.append("E|")
                        .append(edge.from())
                        .append("->")
                        .append(edge.to())
                        .append('\n'));
        return "dcl/" + digest(sb.toString()).substring(0, 24);
    }

    /** Edges sorted by (from, to) for a stable persistence order. */
    public static List<Edge> sortedEdges(Set<Edge> edges) {
        return edges.stream()
                .sorted(Comparator.comparing(Edge::from).thenComparing(Edge::to))
                .toList();
    }

    private static DomainValidationException validation(String message, String field) {
        return validation(message, field, "validation_error");
    }

    private static DomainValidationException validation(String message, String field, String code) {
        return new DomainValidationException(message, code, Map.of("field", field));
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String digest(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm unavailable", exception);
        }
    }
}
