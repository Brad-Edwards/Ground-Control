package com.keplerops.groundcontrol.domain.research.model;

import java.util.List;

/**
 * GC-RSCH-F006 / ADR-078 — a versioned literature-review method profile from the
 * backend-owned methodology catalog. The profile names the required primary
 * methodology sources whose obtained-and-read coverage gates the
 * {@code METHODOLOGY_REQUIREMENTS} artifact for a run that selected this method.
 *
 * <p>Immutable reference data loaded and validated on startup by {@link
 * com.keplerops.groundcontrol.domain.research.service.MethodologyCatalog}; the
 * {@code requiredSources} list is non-empty by construction (the catalog loader
 * rejects a profile with zero required sources). When a run selects this method,
 * {@code profileVersion} and {@code catalogVersion} are snapshotted onto the
 * run-scoped selection so later catalog edits do not rewrite historical runs.
 */
public record MethodProfile(
        String methodKey,
        String label,
        String profileVersion,
        String catalogVersion,
        List<MethodProfileSource> requiredSources) {

    public MethodProfile {
        requiredSources = List.copyOf(requiredSources);
    }
}
