package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/** Activity payload. Schema: {@code gc.workflow.resolve-issue.v1#/$defs/ResolveIssueInput}. */
public record ResolveIssueInput(RepositoryBinding repository, int issueNumber, List<String> requirementUids) {

    public ResolveIssueInput {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
