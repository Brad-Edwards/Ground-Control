package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.riskcontrol.model.MappingEvidenceRef;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlMappingFeedService;
import java.util.List;

public record AssessmentFeedResponse(
        List<RiskControlMappingFeedService.ControlEffectivenessInput> effectivenessInputs,
        List<RiskControlMappingFeedService.ObservationInput> observationInputs,
        List<EvidenceRefSummary> evidenceRefs) {

    public record EvidenceRefSummary(String evidenceRef, String evidenceNote) {
        public static EvidenceRefSummary from(MappingEvidenceRef r) {
            return new EvidenceRefSummary(r.getEvidenceRef(), r.getEvidenceNote());
        }
    }

    public static AssessmentFeedResponse from(RiskControlMappingFeedService.AssessmentFeedResult feed) {
        var evidenceSummaries =
                feed.evidenceRefs().stream().map(EvidenceRefSummary::from).toList();
        return new AssessmentFeedResponse(feed.effectivenessInputs(), feed.observationInputs(), evidenceSummaries);
    }
}
