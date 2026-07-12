package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.content-activities.v1#/$defs/TestQualityReviewResult}. */
public record TestQualityReviewResult(boolean clean, int findings, int cyclesRun) {}
