package com.keplerops.groundcontrol.domain.threatenumeration.service;

import java.util.List;

/**
 * The deterministic output of one threat enumeration run. {@code candidates} is ordered by
 * {@code (elementStableKey, producingRuleId, strideCategory)} so identical inputs always
 * produce byte-stable output (GC-GRC-007 clause a). {@code limitations} carries non-fatal
 * advisories that reduced coverage. Both lists are immutable.
 */
public record ThreatEnumerationResult(
        String schemaVersion,
        String packId,
        String resolvedVersion,
        String checksum,
        String snapshotId,
        String modelVersion,
        List<ThreatCandidate> candidates,
        List<ThreatEnumerationLimitation> limitations) {

    public ThreatEnumerationResult {
        candidates = List.copyOf(candidates);
        limitations = List.copyOf(limitations);
    }
}
