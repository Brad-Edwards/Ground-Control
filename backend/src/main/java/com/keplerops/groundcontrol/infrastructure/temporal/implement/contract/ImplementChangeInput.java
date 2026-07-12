package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.content-activities.v1#/$defs/ImplementChangeInput}. */
public record ImplementChangeInput(int issueNumber, Integer planCommentId, String idempotencyKey) {}
