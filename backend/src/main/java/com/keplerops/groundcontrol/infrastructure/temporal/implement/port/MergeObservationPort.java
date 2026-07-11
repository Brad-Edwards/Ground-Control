package com.keplerops.groundcontrol.infrastructure.temporal.implement.port;

import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;

/**
 * The single synchronous human gate, factored into its own seam (GC-O009 (b), ADR-029): observe the
 * authoritative GitHub merge fact for a resolved {@link RepositoryBinding} and PR number. PR merge is
 * observed from GitHub — it is never a Temporal signal, a REST "approve merge" action, or a console
 * approval button.
 *
 * <p>Split out of {@link GitHubWorkflowPort} so the human-gate slice (#1279) can ship a real,
 * production-wired observation adapter without the rest of the GitHub side-effect surface (branch/PR
 * creation, CI, close), whose adapter lands later. The seam is deliberately "observe PR merge fact for
 * a resolved repository binding", not "call GitHub from the workflow": a polling adapter satisfies it
 * today, and a webhook-fed observation store could feed the same typed activity later without changing
 * the workflow's await logic or any operator-signal name.
 *
 * <p>The workflow observes this fact through the typed {@code observeMergeState} activity; because
 * Temporal executes activities at-least-once the implementation must be a pure read (idempotent) — it
 * reports the current merge state and never mutates GitHub.
 */
public interface MergeObservationPort {

    /** Observe merge state — the authoritative GitHub event, read for a project-resolved binding. */
    MergeObservationResult observeMerge(RepositoryBinding repository, int prNumber);
}
