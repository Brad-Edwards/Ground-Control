package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import java.util.List;
import java.util.UUID;

public record UnmappedThreatsResponse(List<ThreatSummary> threats) {

    public record ThreatSummary(UUID id, String uid, String title) {
        public static ThreatSummary from(ThreatModel t) {
            return new ThreatSummary(t.getId(), t.getUid(), t.getTitle());
        }
    }
}
