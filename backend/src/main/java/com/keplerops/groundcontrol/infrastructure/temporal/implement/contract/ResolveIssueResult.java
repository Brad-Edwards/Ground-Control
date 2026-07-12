package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/** Resolve-issue activity output. Schema: {@code gc.workflow.resolve-issue.v1#/$defs/ResolveIssueResult}. */
public record ResolveIssueResult(int issueNumber, String branch, String baseBranch, List<String> requirementUids) {

    public ResolveIssueResult {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
