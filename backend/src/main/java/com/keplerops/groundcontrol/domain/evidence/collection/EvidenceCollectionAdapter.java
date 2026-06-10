package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.plugins.service.Plugin;

/**
 * Port interface for pluggable evidence collection adapters.
 *
 * <p>Adapters collect evidence from external systems and return normalized
 * commands/results. They do not persist evidence directly; persistence remains
 * owned by the existing evidence domain services.
 */
public interface EvidenceCollectionAdapter extends Plugin {

    EvidenceCollectionOutputSchema outputSchema();

    EvidenceCollectionRateLimit rateLimitPolicy();

    EvidenceCollectionResult collect(EvidenceCollectionRequest request);
}
