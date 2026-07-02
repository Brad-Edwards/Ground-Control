package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.service.SelectMethodologyCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * GC-RSCH-F006 / ADR-078 — select (or re-select) the active methodology for a run.
 * The request carries only {@code methodKey}; the method label, profile/catalog
 * version, and required-source set are derived server-side from the backend-owned
 * methodology catalog and snapshotted as immutable {@code required=true} source
 * rows. Actor is taken from the authenticated server context (ADR-026).
 */
public record SelectMethodologyRequest(@NotBlank @Size(max = 200) String methodKey) {

    public SelectMethodologyCommand toCommand() {
        return new SelectMethodologyCommand(methodKey);
    }
}
