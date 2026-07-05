package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/** Activity payload. Schema: {@code gc.workflow.content-activities.v1#/$defs/FinalReportInput}. */
public record FinalReportInput(int issueNumber, int prNumber, List<String> requirementUids, String idempotencyKey) {

    public FinalReportInput {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
