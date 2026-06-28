package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelAssignment;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelBoundary;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelGap;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelSnapshot;
import java.util.List;

public record BoundaryModelBuildResult(
        BoundaryModelSnapshot snapshot,
        List<BoundaryModelBoundary> boundaries,
        List<BoundaryModelAssignment> assignments,
        List<BoundaryModelGap> gaps) {

    public BoundaryModelBuildResult {
        boundaries = boundaries == null ? List.of() : List.copyOf(boundaries);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }
}
