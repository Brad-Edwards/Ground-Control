package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.git-publish.v1#/$defs/GitPublishResult}. */
public record GitPublishResult(String commitSha, boolean pushed, int precommitAttempts) {}
