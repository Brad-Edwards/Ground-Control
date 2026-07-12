package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.content-activities.v1#/$defs/CodexReviewResult}. */
public record CodexReviewResult(ReviewVerdict verdict, int blockingFindings, int cyclesRun) {}
