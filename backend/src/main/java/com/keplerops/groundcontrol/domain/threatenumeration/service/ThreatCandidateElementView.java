package com.keplerops.groundcontrol.domain.threatenumeration.service;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import java.util.Map;

/**
 * Projection of an {@link ArchitectureModelElementState} onto the fields the enumeration
 * engine needs. Keeps the pure {@link ThreatEnumerationService#enumerate} method decoupled
 * from JPA entities.
 */
public record ThreatCandidateElementView(
        String stableKey,
        ArchitectureModelElementKind elementKind,
        String trustBoundaryKey,
        String dataClassificationKey,
        String flowSourceStableKey,
        String flowTargetStableKey,
        Map<String, Object> metadata) {

    public ThreatCandidateElementView {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Factory from a persisted element state. */
    public static ThreatCandidateElementView from(ArchitectureModelElementState state) {
        return new ThreatCandidateElementView(
                state.getStableKey(),
                state.getElementKind(),
                state.getTrustBoundaryKey(),
                state.getDataClassificationKey(),
                state.getFlowSourceStableKey(),
                state.getFlowTargetStableKey(),
                state.getMetadata());
    }
}
