package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.content-activities.v1#/$defs/ReadinessRecordInput}. */
public record ReadinessRecordInput(int issueNumber, int prNumber, String idempotencyKey) {}
