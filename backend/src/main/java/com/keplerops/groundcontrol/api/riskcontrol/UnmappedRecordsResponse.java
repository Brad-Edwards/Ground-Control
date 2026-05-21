package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import java.util.List;
import java.util.UUID;

public record UnmappedRecordsResponse(List<RecordSummary> records) {

    public record RecordSummary(UUID id, String uid, String title) {
        public static RecordSummary from(RiskRegisterRecord r) {
            return new RecordSummary(r.getId(), r.getUid(), r.getTitle());
        }
    }
}
