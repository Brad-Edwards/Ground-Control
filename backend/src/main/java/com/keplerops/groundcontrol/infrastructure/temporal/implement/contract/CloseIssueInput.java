package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.close-issue.v1#/$defs/CloseIssueInput}. */
public record CloseIssueInput(RepositoryBinding repository, int issueNumber, int prNumber, String idempotencyKey) {}
