package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.content-activities.v1#/$defs/ReadinessRecordResult}. */
public record ReadinessRecordResult(boolean posted, Integer commentId) {}
