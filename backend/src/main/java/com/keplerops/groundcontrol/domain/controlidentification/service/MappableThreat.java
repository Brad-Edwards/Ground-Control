package com.keplerops.groundcontrol.domain.controlidentification.service;

import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatCandidate;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;

/**
 * JPA- and enumeration-decoupled projection of a threat that can be mapped to controls (GC-GRC-008).
 * The pure mapping engine consumes {@code MappableThreat} so it works identically over transient
 * enumeration output ({@link ThreatCandidate}, GC-GRC-007) and persisted, curated {@link ThreatModel}
 * entries.
 *
 * <p>{@code threatRef} is a stable, human-meaningful reference to the source threat (an enumeration
 * candidate's element+rule identity, or a threat-model UID) carried onto every produced candidate and
 * gap for traceability.
 */
public record MappableThreat(String threatRef, ThreatRuleCategory category, StrideCategory strideCategory) {

    public MappableThreat {
        if (threatRef == null || threatRef.isBlank()) {
            throw new IllegalArgumentException("MappableThreat threatRef must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("MappableThreat category must not be null");
        }
    }

    /**
     * Project a deterministic enumeration candidate. The threat reference combines the producing rule
     * and the element stable key so distinct candidates on the same element remain distinguishable.
     */
    public static MappableThreat fromCandidate(ThreatCandidate candidate) {
        String ref = candidate.producingRuleId() + "@" + candidate.elementStableKey();
        return new MappableThreat(ref, candidate.category(), candidate.strideCategory());
    }

    /**
     * Project a curated threat-model entry. A curated threat carries only a STRIDE taxonomy, so it maps
     * against the STRIDE baseline rule category.
     */
    public static MappableThreat fromThreatModel(ThreatModel threatModel) {
        return new MappableThreat(threatModel.getUid(), ThreatRuleCategory.STRIDE_BASELINE, threatModel.getStride());
    }
}
