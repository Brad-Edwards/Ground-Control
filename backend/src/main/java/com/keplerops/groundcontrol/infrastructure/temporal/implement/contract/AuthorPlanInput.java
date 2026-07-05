package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/** Activity payload. Schema: {@code gc.workflow.content-activities.v1#/$defs/AuthorPlanInput}. */
public record AuthorPlanInput(int issueNumber, List<String> requirementUids, String idempotencyKey) {

    public AuthorPlanInput {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
