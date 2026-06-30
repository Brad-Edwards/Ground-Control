package com.keplerops.groundcontrol.domain.dataclassification.service;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;

/**
 * Lightweight projection of an {@code ArchitectureModelElementState} carrying only the fields the
 * lattice evaluator needs (GC-GRC-006). Decoupling evaluation from the JPA entity keeps the core
 * allow/deny logic a pure function that is exercised directly by unit tests.
 */
public record DataClassificationElementView(
        String stableKey,
        ArchitectureModelElementKind elementKind,
        String dataClassificationKey,
        String flowSourceStableKey,
        String flowTargetStableKey,
        ArchitectureFlowDirection flowDirection) {}
