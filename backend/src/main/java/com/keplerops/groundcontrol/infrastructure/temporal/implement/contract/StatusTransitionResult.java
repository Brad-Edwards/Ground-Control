package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import com.keplerops.groundcontrol.domain.requirements.state.Status;

/** Activity payload. Schema: {@code gc.workflow.status-transition.v1#/$defs/StatusTransitionResult}. */
public record StatusTransitionResult(String requirementUid, Status newStatus, boolean transitioned) {}
