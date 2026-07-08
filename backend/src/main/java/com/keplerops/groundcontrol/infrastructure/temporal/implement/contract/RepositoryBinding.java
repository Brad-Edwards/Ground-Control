package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/**
 * A repository coordinate resolved from a Ground Control project, not trusted from caller input.
 *
 * <p>Every privileged GitHub side effect in the deterministic {@code /implement} workflow flows through
 * a {@code RepositoryBinding} produced by the project-scoped {@code resolveRepositoryBinding} activity
 * (ADR-028: workflow executions are scoped to a project via {@code ProjectService} resolution). The
 * workflow input no longer carries {@code repoOwner} / {@code repoName} / {@code baseBranch}; a caller
 * authorized for one project therefore cannot redirect the worker's GitHub credential at another
 * repository. Schema: {@code gc.workflow.repository-binding.v1#/$defs/RepositoryBinding}.
 */
public record RepositoryBinding(String owner, String repo, String baseBranch) {}
