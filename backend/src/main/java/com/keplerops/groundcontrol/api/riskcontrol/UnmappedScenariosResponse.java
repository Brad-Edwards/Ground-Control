package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import java.util.List;
import java.util.UUID;

public record UnmappedScenariosResponse(List<ScenarioSummary> scenarios) {

    public record ScenarioSummary(UUID id, String uid, String title) {
        public static ScenarioSummary from(RiskScenario s) {
            return new ScenarioSummary(s.getId(), s.getUid(), s.getTitle());
        }
    }
}
