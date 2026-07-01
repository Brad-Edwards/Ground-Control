package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.service.RecordMethodologySourceCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * GC-RSCH-F006 — record an optional (additional) methodology source on the active
 * selection. Required sources are derived from the selected method's catalog
 * profile and snapshotted at selection time (ADR-078); sources recorded here are
 * always optional. Actor is taken from the authenticated server context (ADR-026).
 */
public record RecordMethodologySourceRequest(
        @NotBlank @Size(max = 500) String sourceRef, @Size(max = 500) String sourceLabel) {

    public RecordMethodologySourceCommand toCommand() {
        return new RecordMethodologySourceCommand(null, sourceRef, sourceLabel);
    }
}
