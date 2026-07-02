package com.keplerops.groundcontrol.domain.controlidentification.service;

import com.keplerops.groundcontrol.domain.controlidentification.state.ControlCandidateSource;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.Map;
import java.util.UUID;

/**
 * A deterministically derived, per-(threat, rule, control) control suggestion produced by the mapping
 * engine (GC-GRC-008). Candidates are NOT confirmed mitigation — confirmation is a downstream step
 * recorded through {@code RiskControlMapping} and {@code ThreatModelLink MITIGATED_BY}
 * ({@link ControlMappingConfirmationService}).
 *
 * <p>Every candidate carries its implementation guidance (clause b) and full rule provenance
 * (clause b): the producing rule id, the rule-set id/version, the resolved control objective, the
 * candidate's source (pack vs. project) with pack id/version/checksum, and {@code matchedFacts} —
 * the framework selectors and identifiers that fired the match.
 */
public record ControlCandidate(
        String producingRuleId,
        String ruleSetId,
        String ruleSetVersion,
        ThreatRuleCategory threatCategory,
        StrideCategory strideCategory,
        String objectiveKey,
        String threatRef,
        UUID controlId,
        String controlUid,
        ControlCandidateSource source,
        String packId,
        String packVersion,
        String packChecksum,
        String implementationGuidance,
        Map<String, String> matchedFacts,
        String rationale) {

    public ControlCandidate {
        matchedFacts = matchedFacts == null ? Map.of() : Map.copyOf(matchedFacts);
    }
}
