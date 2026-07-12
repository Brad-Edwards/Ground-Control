package com.keplerops.groundcontrol.unit.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.requirements.service.GitHubClient;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubPullRequestData;
import com.keplerops.groundcontrol.infrastructure.github.GitHubMergeObservationAdapter;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.PrState;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the polling merge-observation adapter (GC-O009 (b)): it maps the authoritative
 * GitHub PR state onto the closed {@link PrState} vocabulary and reads the repository coordinates from
 * the project-resolved binding, never from caller input.
 */
class GitHubMergeObservationAdapterTest {

    private static final RepositoryBinding BINDING = new RepositoryBinding("acme", "repo", "dev");

    private final GitHubClient gitHubClient = mock(GitHubClient.class);
    private final GitHubMergeObservationAdapter adapter = new GitHubMergeObservationAdapter(gitHubClient);

    @Test
    void mergedPullRequestObservesMerged() {
        when(gitHubClient.fetchPullRequest("acme", "repo", 7)).thenReturn(pr("CLOSED", true));

        MergeObservationResult result = adapter.observeMerge(BINDING, 7);

        assertThat(result.merged()).isTrue();
        assertThat(result.prState()).isEqualTo(PrState.MERGED);
        // The binding owns the coordinates; the caller never supplies owner/repo.
        verify(gitHubClient).fetchPullRequest("acme", "repo", 7);
    }

    @Test
    void openPullRequestObservesOpenNotMerged() {
        when(gitHubClient.fetchPullRequest("acme", "repo", 7)).thenReturn(pr("OPEN", false));

        MergeObservationResult result = adapter.observeMerge(BINDING, 7);

        assertThat(result.merged()).isFalse();
        assertThat(result.prState()).isEqualTo(PrState.OPEN);
    }

    @Test
    void closedUnmergedPullRequestObservesClosed() {
        when(gitHubClient.fetchPullRequest("acme", "repo", 7)).thenReturn(pr("CLOSED", false));

        MergeObservationResult result = adapter.observeMerge(BINDING, 7);

        assertThat(result.merged()).isFalse();
        assertThat(result.prState()).isEqualTo(PrState.CLOSED);
    }

    private static GitHubPullRequestData pr(String state, boolean merged) {
        return new GitHubPullRequestData(
                7, "title", state, merged, "https://example.test/pr/7", "", "dev", "7-branch", List.of());
    }
}
