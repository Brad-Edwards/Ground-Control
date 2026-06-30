package com.keplerops.groundcontrol.api.dataclassification;

import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationLattice;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeDefinition;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeFactory;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import java.util.List;

/** API response describing a project's active data classification lattice (GC-GRC-006). */
public record DataClassificationLatticeResponse(
        String projectIdentifier,
        String schemaVersion,
        DataClassificationSource source,
        String policyVersion,
        int labelCount,
        int edgeCount,
        List<LabelResponse> labels,
        List<FlowResponse> permittedFlows) {

    public static DataClassificationLatticeResponse from(
            String projectIdentifier, DataClassificationLatticeDefinition definition) {
        var labels = definition.labels().stream()
                .map(label -> new LabelResponse(label.key(), label.displayName(), label.description(), label.rank()))
                .toList();
        var flows = DataClassificationLatticeFactory.sortedEdges(definition.permittedFlows()).stream()
                .map(edge -> new FlowResponse(edge.from(), edge.to()))
                .toList();
        return new DataClassificationLatticeResponse(
                projectIdentifier,
                DataClassificationLattice.SCHEMA_VERSION,
                definition.source(),
                definition.policyVersion(),
                definition.labelCount(),
                definition.edgeCount(),
                labels,
                flows);
    }

    public record LabelResponse(String key, String displayName, String description, Integer rank) {}

    public record FlowResponse(String from, String to) {}
}
