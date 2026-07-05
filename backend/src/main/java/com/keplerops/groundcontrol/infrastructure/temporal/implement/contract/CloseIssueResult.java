package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.close-issue.v1#/$defs/CloseIssueResult}. */
public record CloseIssueResult(boolean closed, boolean alreadyClosed) {}
