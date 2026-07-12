package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.git-publish.v1#/$defs/GitPublishInput}. */
public record GitPublishInput(
        String branch, String commitMessage, Integer maxPrecommitRetries, String idempotencyKey) {}
