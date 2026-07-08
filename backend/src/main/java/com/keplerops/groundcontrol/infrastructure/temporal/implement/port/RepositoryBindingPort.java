package com.keplerops.groundcontrol.infrastructure.temporal.implement.port;

import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;

/**
 * Infrastructure port that resolves a Ground Control project to its canonical {@link RepositoryBinding}
 * (owner, repo, base branch) through the project-scoped configuration boundary (ADR-027/ADR-028).
 *
 * <p>This is the seam that keeps privileged GitHub side effects project-scoped: the deterministic
 * {@code /implement} workflow resolves the binding once from the run's {@code project} and never trusts
 * caller-supplied repository coordinates. Interface only in phase 2 (issue #1277); the config-backed
 * adapter lands with the control-surface phase.
 */
public interface RepositoryBindingPort {

    /** Resolve the project's repository binding, or throw a domain exception when the project is unknown. */
    RepositoryBinding resolve(String project);
}
