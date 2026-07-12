package com.keplerops.groundcontrol.domain.workflowexecution;

/**
 * Workflow-ID scheme for the product control surface (GC-O009 phase 3, ADR-028).
 *
 * <p>Project scoping is enforced by <em>workflow-ID partitioning</em> (ADR-028: "partition workflow
 * IDs and Search Attributes by project"): every {@code /implement} execution is started under
 * {@code gc-implement-<project>-<issueNumber>}. The id is deliberately <strong>slash-free</strong> so
 * it is a single URL path segment usable in {@code /{workflowId}} routes and the {@code *} security
 * matcher, and it lets {@link WorkflowExecutionService} prove an execution belongs to the resolved
 * project before any status read or signal — a cross-project id resolves to not-found without leaking
 * whether it exists in another project.
 *
 * <p>The scheme is a plain string convention (no Temporal types), so it lives in the domain and is
 * shared by both the service (scope checks) and the infrastructure adapter (id construction).
 */
public final class WorkflowExecutionId {

    private static final String IMPLEMENT_PREFIX = "gc-implement-";

    private WorkflowExecutionId() {}

    /** Prefix that every workflow id for {@code project}'s {@code /implement} runs starts with. */
    public static String implementProjectPrefix(String project) {
        return IMPLEMENT_PREFIX + project + "-";
    }

    /** Deterministic workflow id for {@code project}'s {@code /implement} run on {@code issueNumber}. */
    public static String forImplement(String project, int issueNumber) {
        return implementProjectPrefix(project) + issueNumber;
    }

    /**
     * True when {@code workflowId} is an {@code /implement} id owned by {@code project}. The suffix
     * after the project prefix must be a non-empty run of digits (the issue number), which resolves
     * the prefix ambiguity between a project {@code "a"} and a project {@code "a-b"}.
     */
    public static boolean belongsToProject(String workflowId, String project) {
        if (workflowId == null || project == null) {
            return false;
        }
        String prefix = implementProjectPrefix(project);
        if (!workflowId.startsWith(prefix)) {
            return false;
        }
        String suffix = workflowId.substring(prefix.length());
        return !suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit);
    }
}
