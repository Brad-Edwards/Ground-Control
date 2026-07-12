package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.open-pull-request.v1#/$defs/OpenPullRequestResult}. */
public record OpenPullRequestResult(int prNumber, String url) {}
