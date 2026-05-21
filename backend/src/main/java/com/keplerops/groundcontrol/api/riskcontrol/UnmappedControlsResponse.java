package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import java.util.List;
import java.util.UUID;

public record UnmappedControlsResponse(List<ControlSummary> controls) {

    public record ControlSummary(UUID id, String uid, String title) {
        public static ControlSummary from(Control c) {
            return new ControlSummary(c.getId(), c.getUid(), c.getTitle());
        }
    }
}
