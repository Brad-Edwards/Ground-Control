package com.keplerops.groundcontrol.infrastructure.temporal.implement.port;

import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CloseIssueResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.OpenPullRequestResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;
import java.util.List;

/**
 * Infrastructure port for the GitHub side effects the deterministic {@code /implement} activities
 * drive (issue/branch resolution, PR creation, CI-check and merge observation, issue close).
 *
 * <p>Every method takes a project-resolved {@link RepositoryBinding} rather than caller-supplied
 * owner/name strings, so the side-effect seam is bound to the run's Ground Control project (ADR-028)
 * and an adapter cannot be pointed at an arbitrary repository. Mutating methods take a stable
 * {@code idempotencyKey}: because Temporal executes activities at-least-once, implementations MUST be
 * idempotent — observe-before-create keyed by {@code idempotencyKey} — so a retry after a partial
 * success does not create a duplicate branch, pull request, or issue action.
 *
 * <p>Phase 2 (issue #1277) defines the port; the concrete GitHub adapter and full merge-observation
 * (webhook/polling) implementation land with the control-surface and human-gate phases (#1278/#1279).
 * Activities are typed against this port so tests supply fakes and the engine core is exercised in the
 * Temporal test environment without a live GitHub dependency.
 */
public interface GitHubWorkflowPort {

    /** Resolve (idempotently creating if absent) the issue's feature branch off the binding's base branch. */
    String developBranch(RepositoryBinding repository, int issueNumber);

    /** Open a pull request (idempotent by {@code idempotencyKey}); returns the created PR number and URL. */
    OpenPullRequestResult openPullRequest(
            RepositoryBinding repository, String headBranch, String title, String idempotencyKey);

    /** Observe the aggregate CI state for a pull request. */
    CiObservationResult observeCi(RepositoryBinding repository, int prNumber);

    /** Observe merge state — the single synchronous human gate, read as the authoritative GitHub event. */
    MergeObservationResult observeMerge(RepositoryBinding repository, int prNumber);

    /** List the changed file paths of the merged pull request (for traceability reconciliation). */
    List<String> changedFiles(RepositoryBinding repository, int prNumber);

    /** Close the issue (merge-gated by the caller), idempotent by {@code idempotencyKey} and on an already-closed issue. */
    CloseIssueResult closeIssue(RepositoryBinding repository, int issueNumber, String idempotencyKey);
}
