package com.keplerops.groundcontrol.domain.controlidentification.service;

import java.util.List;

/**
 * The deterministic output of one control-identification run (GC-GRC-008). {@code candidates} is
 * ordered by {@code (threatRef, producingRuleId, controlUid)} and {@code gaps} by
 * {@code (threatRef, producingRuleId, objectiveKey)} so identical inputs always produce byte-stable
 * output. {@code ruleSetId}/{@code ruleSetVersion} identify the rule set that produced the run. Both
 * lists are immutable.
 */
public record ControlIdentificationResult(
        String schemaVersion,
        String ruleSetId,
        String ruleSetVersion,
        List<ControlCandidate> candidates,
        List<ControlIdentificationGap> gaps) {

    public ControlIdentificationResult {
        candidates = List.copyOf(candidates);
        gaps = List.copyOf(gaps);
    }
}
