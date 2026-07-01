package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.service.UpdateMethodologySourceStateCommand;
import jakarta.validation.constraints.NotNull;

/**
 * GC-RSCH-F006 — update the state of a methodology source.
 */
public record UpdateMethodologySourceStateRequest(@NotNull MethodologySourceState state) {

    public UpdateMethodologySourceStateCommand toCommand() {
        return new UpdateMethodologySourceStateCommand(state);
    }
}
