package com.keplerops.groundcontrol.domain.architecturemodel.service;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import java.util.List;

public record ArchitectureModelSnapshotView(
        ArchitectureModelSnapshot snapshot, List<ArchitectureModelElementState> states) {

    public ArchitectureModelSnapshotView {
        states = states == null ? List.of() : List.copyOf(states);
    }
}
