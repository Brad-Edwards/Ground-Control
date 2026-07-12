package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.open-pull-request.v1#/$defs/OpenPullRequestInput}. */
public record OpenPullRequestInput(
        RepositoryBinding repository, String headBranch, String title, String idempotencyKey) {}
