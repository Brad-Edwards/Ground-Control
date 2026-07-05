package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import com.keplerops.groundcontrol.domain.requirements.state.Status;

/** Activity payload. Schema: {@code gc.workflow.status-transition.v1#/$defs/StatusTransitionInput}. */
public record StatusTransitionInput(Status targetStatus, String project, String requirementUid) {}
