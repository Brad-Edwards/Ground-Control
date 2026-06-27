package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.service.FailRunCommand;
import jakarta.validation.constraints.Size;

/** Fail a run with a bounded failure observation (no stack traces / raw content). */
public record FailRunRequest(
        @Size(max = 100) String errorCode, @Size(max = 40) String errorClass, @Size(max = 500) String errorSummary) {

    public FailRunCommand toCommand() {
        return new FailRunCommand(errorCode, errorClass, errorSummary);
    }
}
