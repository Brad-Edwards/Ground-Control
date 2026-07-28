package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.GitHubPullRequestSync;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementImport;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.GitHubIssueSyncRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.GitHubPullRequestSyncRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementImportRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubClient;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubIssueSyncService;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubPullRequestData;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.PullRequestState;
import com.keplerops.groundcontrol.domain.requirements.state.SyncStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from GitHubIssueSyncServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class GitHubIssueSyncServicePrSyncTest {
    @Mock
    private GitHubClient gitHubClient;

    @Mock
    private GitHubIssueSyncRepository issueSyncRepository;

    @Mock
    private GitHubPullRequestSyncRepository prSyncRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Mock
    private RequirementImportRepository importRepository;

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private TraceabilityService traceabilityService;

    @InjectMocks
    private GitHubIssueSyncService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", PROJECT_ID);
        return project;
    }

    private static void setField(Object obj, String fieldName, Object value) {
        TestUtil.setField(obj, fieldName, value);
    }

    private void stubAuditSave() {
        when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
            var audit = inv.<RequirementImport>getArgument(0);
            setField(audit, "id", UUID.randomUUID());
            setField(audit, "importedAt", Instant.now());
            return audit;
        });
    }

    // -----------------------------------------------------------------------
    // Pull request sync tests
    // -----------------------------------------------------------------------

    @Nested
    class PrSync {

        @Test
        void createsNewPrSync() {
            var pr = new GitHubPullRequestData(
                    10,
                    "Add feature",
                    "OPEN",
                    false,
                    "https://github.com/o/r/pull/10",
                    "body",
                    "main",
                    "feat/x",
                    List.of());

            when(gitHubClient.fetchAllPullRequests("owner", "repo")).thenReturn(List.of(pr));
            when(prSyncRepository.findByRepoAndPrNumber("owner/repo", 10)).thenReturn(Optional.empty());
            when(prSyncRepository.save(any(GitHubPullRequestSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.PULL_REQUEST))
                    .thenReturn(List.of());
            stubAuditSave();

            var result = service.syncGitHubPullRequests("owner", "repo");

            assertThat(result.prsCreated()).isEqualTo(1);
            assertThat(result.prsUpdated()).isZero();
            verify(prSyncRepository).save(any(GitHubPullRequestSync.class));
        }

        @Test
        void updatesExistingPrSync() {
            var pr = new GitHubPullRequestData(
                    10,
                    "Updated PR",
                    "CLOSED",
                    true,
                    "https://github.com/o/r/pull/10",
                    "updated body",
                    "main",
                    "feat/x",
                    List.of());
            var existing = new GitHubPullRequestSync(
                    "owner/repo", 10, "Old PR", PullRequestState.OPEN, "https://github.com/o/r/pull/10", Instant.now());

            when(gitHubClient.fetchAllPullRequests("owner", "repo")).thenReturn(List.of(pr));
            when(prSyncRepository.findByRepoAndPrNumber("owner/repo", 10)).thenReturn(Optional.of(existing));
            when(prSyncRepository.save(any(GitHubPullRequestSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.PULL_REQUEST))
                    .thenReturn(List.of());
            stubAuditSave();

            var result = service.syncGitHubPullRequests("owner", "repo");

            assertThat(result.prsUpdated()).isEqualTo(1);
            assertThat(result.prsCreated()).isZero();
            assertThat(existing.getPrTitle()).isEqualTo("Updated PR");
            assertThat(existing.getPrState()).isEqualTo(PullRequestState.MERGED);
        }

        @Test
        void updatesPrTraceabilityLinks() {
            var sync = new GitHubPullRequestSync(
                    "owner/repo",
                    10,
                    "Ship feature",
                    PullRequestState.MERGED,
                    "https://github.com/o/r/pull/10",
                    Instant.now());
            setField(sync, "id", UUID.randomUUID());
            when(prSyncRepository.findByRepoAndPrNumber("owner/repo", 10)).thenReturn(Optional.of(sync));

            var requirement = new Requirement(TEST_PROJECT, "GC-A001", "Test", "statement");
            setField(requirement, "id", UUID.randomUUID());
            var link = new TraceabilityLink(requirement, ArtifactType.PULL_REQUEST, "10", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.PULL_REQUEST))
                    .thenReturn(List.of(link));
            when(traceabilityLinkRepository.save(any(TraceabilityLink.class))).thenAnswer(inv -> inv.getArgument(0));

            when(gitHubClient.fetchAllPullRequests(anyString(), anyString())).thenReturn(List.of());
            stubAuditSave();

            service.syncGitHubPullRequests("owner", "repo");

            assertThat(link.getArtifactTitle()).isEqualTo("#10 - Ship feature [MERGED]");
            assertThat(link.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        }
    }

    @Nested
    class PrStateParsing {

        @Test
        void mergedPrIsSavedAsMerged() {
            var pr = new GitHubPullRequestData(
                    1, "Merged PR", "CLOSED", true, "https://github.com/o/r/pull/1", "", "main", "feat", List.of());

            when(gitHubClient.fetchAllPullRequests("owner", "repo")).thenReturn(List.of(pr));
            when(prSyncRepository.findByRepoAndPrNumber("owner/repo", 1)).thenReturn(Optional.empty());
            when(prSyncRepository.save(any(GitHubPullRequestSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.PULL_REQUEST))
                    .thenReturn(List.of());
            stubAuditSave();

            service.syncGitHubPullRequests("owner", "repo");

            ArgumentCaptor<GitHubPullRequestSync> captor = ArgumentCaptor.forClass(GitHubPullRequestSync.class);
            verify(prSyncRepository).save(captor.capture());
            assertThat(captor.getValue().getPrState()).isEqualTo(PullRequestState.MERGED);
        }

        @Test
        void closedNotMergedPrIsSavedAsClosed() {
            var pr = new GitHubPullRequestData(
                    2, "Closed PR", "CLOSED", false, "https://github.com/o/r/pull/2", "", "main", "feat", List.of());

            when(gitHubClient.fetchAllPullRequests("owner", "repo")).thenReturn(List.of(pr));
            when(prSyncRepository.findByRepoAndPrNumber("owner/repo", 2)).thenReturn(Optional.empty());
            when(prSyncRepository.save(any(GitHubPullRequestSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.PULL_REQUEST))
                    .thenReturn(List.of());
            stubAuditSave();

            service.syncGitHubPullRequests("owner", "repo");

            ArgumentCaptor<GitHubPullRequestSync> captor = ArgumentCaptor.forClass(GitHubPullRequestSync.class);
            verify(prSyncRepository).save(captor.capture());
            assertThat(captor.getValue().getPrState()).isEqualTo(PullRequestState.CLOSED);
        }

        @Test
        void unknownPrStateDefaultsToOpen() {
            var pr = new GitHubPullRequestData(
                    3,
                    "Unknown PR",
                    "INVALID_STATE",
                    false,
                    "https://github.com/o/r/pull/3",
                    "",
                    "main",
                    "feat",
                    List.of());

            when(gitHubClient.fetchAllPullRequests("owner", "repo")).thenReturn(List.of(pr));
            when(prSyncRepository.findByRepoAndPrNumber("owner/repo", 3)).thenReturn(Optional.empty());
            when(prSyncRepository.save(any(GitHubPullRequestSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.PULL_REQUEST))
                    .thenReturn(List.of());
            stubAuditSave();

            var result = service.syncGitHubPullRequests("owner", "repo");

            assertThat(result.prsCreated()).isEqualTo(1);
            assertThat(result.errors()).isEmpty();

            ArgumentCaptor<GitHubPullRequestSync> captor = ArgumentCaptor.forClass(GitHubPullRequestSync.class);
            verify(prSyncRepository).save(captor.capture());
            assertThat(captor.getValue().getPrState()).isEqualTo(PullRequestState.OPEN);
        }
    }
}
