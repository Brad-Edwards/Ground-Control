package com.keplerops.groundcontrol.infrastructure.github;

import com.keplerops.groundcontrol.domain.requirements.service.GitHubClient;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubPullRequestData;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.PrState;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.port.MergeObservationPort;
import org.springframework.stereotype.Component;

/**
 * Polling-backed implementation of {@link MergeObservationPort} (GC-O009 (b), ADR-029): reads the
 * authoritative GitHub merge fact for a resolved {@link RepositoryBinding} via the existing
 * {@link GitHubClient} (the server-side {@code gh} CLI seam) — the workflow's single synchronous human
 * gate is <em>observed</em>, never signaled.
 *
 * <p>Pure read, no mutation: the {@code observeMergeState} activity is at-least-once, so re-observing a
 * PR simply re-reports its current state. Only the bounded, redacted {@link MergeObservationResult}
 * ({@code merged} + {@link PrState}) crosses the seam — never GitHub tokens, PR bodies, or raw API
 * payloads, so nothing sensitive reaches Temporal history, Search Attributes, logs, or the read model.
 *
 * <p>The repository owner/repo come from the project-resolved binding, never from caller input, so a
 * caller authorized for one project cannot redirect the observation at another repository. A future
 * webhook receiver could feed the same typed result without changing this seam or the workflow await.
 */
@Component
public class GitHubMergeObservationAdapter implements MergeObservationPort {

    private final GitHubClient gitHubClient;

    public GitHubMergeObservationAdapter(GitHubClient gitHubClient) {
        this.gitHubClient = gitHubClient;
    }

    @Override
    public MergeObservationResult observeMerge(RepositoryBinding repository, int prNumber) {
        GitHubPullRequestData pr = gitHubClient.fetchPullRequest(repository.owner(), repository.repo(), prNumber);
        return new MergeObservationResult(pr.merged(), toPrState(pr));
    }

    /**
     * Map GitHub's {@code merged}/{@code state} onto the closed {@link PrState} vocabulary. A merged PR
     * is reported as {@link PrState#MERGED} even though GitHub also marks it {@code closed}, so the
     * workflow distinguishes "merged" (advance to Phase E) from "closed without merge" (abandoned).
     */
    private static PrState toPrState(GitHubPullRequestData pr) {
        if (pr.merged()) {
            return PrState.MERGED;
        }
        return "OPEN".equalsIgnoreCase(pr.state()) ? PrState.OPEN : PrState.CLOSED;
    }
}
