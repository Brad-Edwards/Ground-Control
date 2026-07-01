package com.keplerops.groundcontrol.domain.controlidentification.service;

import java.util.List;
import java.util.UUID;

/**
 * The confirmed control coverage of a single threat (GC-GRC-008): the controls recorded as covering the
 * threat through the canonical mapping aggregates, ordered by control UID for stable output.
 */
public record ThreatControlCoverage(UUID threatModelId, List<CoveredControl> controls) {

    public ThreatControlCoverage {
        controls = List.copyOf(controls);
    }
}
