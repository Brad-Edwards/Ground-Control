package com.keplerops.groundcontrol.domain.controlidentification.service;

import com.keplerops.groundcontrol.domain.controlidentification.state.ControlIdentificationGapReason;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;

/**
 * An explicit control-design gap (GC-GRC-008 clause d): a mapping rule fired for a threat but no
 * available control matched its objective. Surfaced for human/agent control design rather than
 * silently dropped, so missing coverage is visible in the derivation-backed GRC output.
 */
public record ControlIdentificationGap(
        ThreatRuleCategory threatCategory,
        StrideCategory strideCategory,
        String objectiveKey,
        String producingRuleId,
        String threatRef,
        ControlIdentificationGapReason reason,
        String description) {}
