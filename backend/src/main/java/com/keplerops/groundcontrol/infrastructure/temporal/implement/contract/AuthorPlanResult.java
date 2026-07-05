package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.content-activities.v1#/$defs/AuthorPlanResult}. */
public record AuthorPlanResult(boolean posted, Integer commentId) {}
