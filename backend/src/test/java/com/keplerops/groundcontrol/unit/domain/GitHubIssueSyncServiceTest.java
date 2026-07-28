package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.GitHubIssueSync;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementImport;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.GitHubIssueSyncRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.GitHubPullRequestSyncRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementImportRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.CreateGitHubIssueCommand;
import com.keplerops.groundcontrol.domain.requirements.service.CreateTraceabilityLinkCommand;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubClient;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubIssueData;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubIssueSyncService;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.IssueState;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.SyncStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from GitHubIssueSyncServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class GitHubIssueSyncServiceTest {
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

    private GitHubIssueSyncService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", PROJECT_ID);
        return project;
    }

    @BeforeEach
    void setUp() {
        service = new GitHubIssueSyncService(
                gitHubClient,
                issueSyncRepository,
                prSyncRepository,
                traceabilityLinkRepository,
                importRepository,
                requirementRepository,
                traceabilityService);
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

    @Nested
    class UpsertIssueSync {

        @Test
        void createsNewIssueSync() {
            var issue = new GitHubIssueData(
                    1, "Bug fix", "OPEN", "https://github.com/o/r/issues/1", "Fix the bug", List.of("bug"));

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue));
            when(issueSyncRepository.findByIssueNumber(1)).thenReturn(Optional.empty());
            when(issueSyncRepository.save(any(GitHubIssueSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of());
            stubAuditSave();

            var result = service.syncGitHubIssues("owner", "repo");

            assertThat(result.issuesCreated()).isEqualTo(1);
            assertThat(result.issuesUpdated()).isZero();
            verify(issueSyncRepository).save(any(GitHubIssueSync.class));
        }

        @Test
        void updatesExistingIssueSync() {
            var issue = new GitHubIssueData(
                    1, "Updated title", "CLOSED", "https://github.com/o/r/issues/1", "Updated body", List.of("bug"));
            var existing = new GitHubIssueSync(
                    1, "Old title", IssueState.OPEN, "https://github.com/o/r/issues/1", Instant.now());

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue));
            when(issueSyncRepository.findByIssueNumber(1)).thenReturn(Optional.of(existing));
            when(issueSyncRepository.save(any(GitHubIssueSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of());
            stubAuditSave();

            var result = service.syncGitHubIssues("owner", "repo");

            assertThat(result.issuesUpdated()).isEqualTo(1);
            assertThat(result.issuesCreated()).isZero();
            assertThat(existing.getIssueTitle()).isEqualTo("Updated title");
            assertThat(existing.getIssueState()).isEqualTo(IssueState.CLOSED);
        }
    }

    @Nested
    class LabelParsing {

        @Test
        void extractsPhaseFromLabels() {
            var issue = new GitHubIssueData(
                    99, "Phase test", "OPEN", "https://github.com/o/r/issues/99", "", List.of("bug", "phase-2"));

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue));
            when(issueSyncRepository.findByIssueNumber(99)).thenReturn(Optional.empty());
            when(issueSyncRepository.save(any(GitHubIssueSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of());
            stubAuditSave();

            service.syncGitHubIssues("owner", "repo");

            ArgumentCaptor<GitHubIssueSync> captor = ArgumentCaptor.forClass(GitHubIssueSync.class);
            verify(issueSyncRepository).save(captor.capture());
            assertThat(captor.getValue().getPhase()).isEqualTo(2);
        }

        @Test
        void extractsPriorityFromLabels() {
            var issue = new GitHubIssueData(
                    99, "Priority test", "OPEN", "https://github.com/o/r/issues/99", "", List.of("phase-1", "P1"));

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue));
            when(issueSyncRepository.findByIssueNumber(99)).thenReturn(Optional.empty());
            when(issueSyncRepository.save(any(GitHubIssueSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of());
            stubAuditSave();

            service.syncGitHubIssues("owner", "repo");

            ArgumentCaptor<GitHubIssueSync> captor = ArgumentCaptor.forClass(GitHubIssueSync.class);
            verify(issueSyncRepository).save(captor.capture());
            assertThat(captor.getValue().getPriorityLabel()).isEqualTo("P1");
        }
    }

    @Nested
    class CrossReferences {

        @Test
        void extractsCrossReferencesFromBody() {
            var issue = new GitHubIssueData(
                    42, "XRef test", "OPEN", "https://github.com/o/r/issues/42", "Depends on #10 and #20", List.of());

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue));
            when(issueSyncRepository.findByIssueNumber(42)).thenReturn(Optional.empty());
            when(issueSyncRepository.save(any(GitHubIssueSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of());
            stubAuditSave();

            service.syncGitHubIssues("owner", "repo");

            ArgumentCaptor<GitHubIssueSync> captor = ArgumentCaptor.forClass(GitHubIssueSync.class);
            verify(issueSyncRepository).save(captor.capture());
            assertThat(captor.getValue().getCrossReferences()).containsExactly(10, 20);
        }

        @Test
        void filtersSelfReferencesFromCrossRefs() {
            var issue = new GitHubIssueData(
                    42, "Self-ref test", "OPEN", "https://github.com/o/r/issues/42", "See #42", List.of());

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue));
            when(issueSyncRepository.findByIssueNumber(42)).thenReturn(Optional.empty());
            when(issueSyncRepository.save(any(GitHubIssueSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of());
            stubAuditSave();

            service.syncGitHubIssues("owner", "repo");

            ArgumentCaptor<GitHubIssueSync> captor = ArgumentCaptor.forClass(GitHubIssueSync.class);
            verify(issueSyncRepository).save(captor.capture());
            assertThat(captor.getValue().getCrossReferences()).isEmpty();
        }
    }

    @Nested
    class TraceabilityLinkUpdates {

        @Test
        void updatesTraceabilityLinksWithSyncedData() {
            var issue = new GitHubIssueData(10, "Issue 10", "OPEN", "https://github.com/o/r/issues/10", "", List.of());
            var sync = new GitHubIssueSync(
                    10, "Issue 10", IssueState.OPEN, "https://github.com/o/r/issues/10", Instant.now());

            var link = new TraceabilityLink(null, ArtifactType.GITHUB_ISSUE, "10", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue));
            when(issueSyncRepository.findByIssueNumber(10))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(sync));
            when(issueSyncRepository.save(any(GitHubIssueSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of(link));
            when(traceabilityLinkRepository.save(any(TraceabilityLink.class))).thenAnswer(inv -> inv.getArgument(0));
            stubAuditSave();

            var result = service.syncGitHubIssues("owner", "repo");

            assertThat(result.linksUpdated()).isEqualTo(1);
            assertThat(link.getArtifactUrl()).isEqualTo("https://github.com/o/r/issues/10");
            assertThat(link.getArtifactTitle()).isEqualTo("#10 - Issue 10 [OPEN]");
            assertThat(link.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        }

        @Test
        void collectsErrorWhenLinkUpdateFails() {
            var issue = new GitHubIssueData(10, "Issue 10", "OPEN", "https://github.com/o/r/issues/10", "", List.of());
            var sync = new GitHubIssueSync(
                    10, "Issue 10", IssueState.OPEN, "https://github.com/o/r/issues/10", Instant.now());

            var link = new TraceabilityLink(null, ArtifactType.GITHUB_ISSUE, "10", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue));
            when(issueSyncRepository.findByIssueNumber(10))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(sync));
            when(issueSyncRepository.save(any(GitHubIssueSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of(link));
            when(traceabilityLinkRepository.save(any(TraceabilityLink.class)))
                    .thenThrow(new RuntimeException("DB save failed"));
            stubAuditSave();

            var result = service.syncGitHubIssues("owner", "repo");

            assertThat(result.linksUpdated()).isZero();
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).phase()).isEqualTo("traceability");
            assertThat(result.errors().get(0).artifactIdentifier()).isEqualTo("10");
            assertThat(result.errors().get(0).error()).isEqualTo("DB save failed");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void collectsErrorsAndContinues() {
            var issue1 = new GitHubIssueData(1, "Issue 1", "OPEN", "url1", "", List.of());
            var issue2 = new GitHubIssueData(2, "Issue 2", "OPEN", "url2", "", List.of());

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue1, issue2));
            when(issueSyncRepository.findByIssueNumber(1)).thenReturn(Optional.empty());
            when(issueSyncRepository.findByIssueNumber(2)).thenReturn(Optional.empty());
            when(issueSyncRepository.save(any(GitHubIssueSync.class)))
                    .thenThrow(new RuntimeException("DB save failed"))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of());
            stubAuditSave();

            var result = service.syncGitHubIssues("owner", "repo");

            assertThat(result.issuesCreated()).isEqualTo(1);
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).issue()).isEqualTo(1);
        }

        @Test
        void unknownIssueStateDefaultsToOpenInsteadOfFailing() {
            var issue = new GitHubIssueData(1, "Issue 1", "INVALID_STATE", "url1", "", List.of());

            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of(issue));
            when(issueSyncRepository.findByIssueNumber(1)).thenReturn(Optional.empty());
            when(issueSyncRepository.save(any(GitHubIssueSync.class))).thenAnswer(inv -> inv.getArgument(0));
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of());
            stubAuditSave();

            var result = service.syncGitHubIssues("owner", "repo");

            assertThat(result.issuesCreated()).isEqualTo(1);
            assertThat(result.errors()).isEmpty();
        }
    }

    @Nested
    class AuditRecord {

        @Test
        void savesAuditRecord() {
            when(gitHubClient.fetchAllIssues("owner", "repo")).thenReturn(List.of());
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of());
            stubAuditSave();

            service.syncGitHubIssues("owner", "repo");

            ArgumentCaptor<RequirementImport> captor = ArgumentCaptor.forClass(RequirementImport.class);
            verify(importRepository).save(captor.capture());
            assertThat(captor.getValue().getSourceType())
                    .isEqualTo(com.keplerops.groundcontrol.domain.requirements.state.ImportSourceType.GITHUB);
            assertThat(captor.getValue().getSourceFile()).isEqualTo("owner/repo");
        }
    }

    @Nested
    class IssueCreation {

        @Test
        void createsIssueAndTraceabilityLink() {
            var requirement = new Requirement(TEST_PROJECT, "GC-A001", "Test Title", "Test statement");
            setField(requirement, "id", UUID.randomUUID());

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "GC-A001"))
                    .thenReturn(Optional.of(requirement));

            var issueData = new GitHubIssueData(
                    42, "GC-A001: Test Title", "OPEN", "https://github.com/o/r/issues/42", "body", List.of());
            when(gitHubClient.createIssue(anyString(), anyString(), anyString(), anyList()))
                    .thenReturn(issueData);

            var traceLink = new TraceabilityLink(requirement, ArtifactType.GITHUB_ISSUE, "42", LinkType.IMPLEMENTS);
            setField(traceLink, "id", UUID.randomUUID());
            when(traceabilityService.createLinkUnchecked(any(UUID.class), any()))
                    .thenReturn(traceLink);

            var command = new CreateGitHubIssueCommand(PROJECT_ID, "GC-A001", "o/r", null, List.of());
            var result = service.createIssueFromRequirement(command);

            assertThat(result.issueUrl()).isEqualTo("https://github.com/o/r/issues/42");
            assertThat(result.issueNumber()).isEqualTo(42);
            assertThat(result.traceabilityLinkId()).isNotNull();
            assertThat(result.warning()).isNull();
        }

        @Test
        void storesTraceabilityLinkWithRawIntegerIdentifier() {
            var requirement = new Requirement(TEST_PROJECT, "GC-A001", "Test Title", "Test statement");
            setField(requirement, "id", UUID.randomUUID());

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "GC-A001"))
                    .thenReturn(Optional.of(requirement));

            var issueData = new GitHubIssueData(
                    42, "GC-A001: Test Title", "OPEN", "https://github.com/o/r/issues/42", "body", List.of());
            when(gitHubClient.createIssue(anyString(), anyString(), anyString(), anyList()))
                    .thenReturn(issueData);

            var traceLink = new TraceabilityLink(requirement, ArtifactType.GITHUB_ISSUE, "42", LinkType.IMPLEMENTS);
            setField(traceLink, "id", UUID.randomUUID());
            when(traceabilityService.createLinkUnchecked(any(UUID.class), any()))
                    .thenReturn(traceLink);

            var command = new CreateGitHubIssueCommand(PROJECT_ID, "GC-A001", "o/r", null, List.of());
            service.createIssueFromRequirement(command);

            ArgumentCaptor<CreateTraceabilityLinkCommand> captor =
                    ArgumentCaptor.forClass(CreateTraceabilityLinkCommand.class);
            verify(traceabilityService).createLinkUnchecked(any(UUID.class), captor.capture());
            assertThat(captor.getValue().artifactIdentifier()).isEqualTo("42");
            assertThat(captor.getValue().artifactIdentifier()).doesNotStartWith("#");
        }
    }

    @Nested
    class TraceabilityLinkStateReflection {

        @Test
        void includesIssueStateInTraceabilityLinkTitle() {
            var sync = new GitHubIssueSync(
                    42, "Fix login bug", IssueState.CLOSED, "https://github.com/o/r/issues/42", Instant.now());
            setField(sync, "id", UUID.randomUUID());
            when(issueSyncRepository.findByIssueNumber(42)).thenReturn(Optional.of(sync));

            var requirement = new Requirement(TEST_PROJECT, "GC-A001", "Test", "statement");
            setField(requirement, "id", UUID.randomUUID());
            var link = new TraceabilityLink(requirement, ArtifactType.GITHUB_ISSUE, "42", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            when(traceabilityLinkRepository.findByArtifactType(ArtifactType.GITHUB_ISSUE))
                    .thenReturn(List.of(link));

            when(gitHubClient.fetchAllIssues(anyString(), anyString())).thenReturn(List.of());
            stubAuditSave();

            service.syncGitHubIssues("KeplerOps", "Ground-Control");

            assertThat(link.getArtifactTitle()).contains("[CLOSED]");
            assertThat(link.getArtifactTitle()).startsWith("#42 -");
            assertThat(link.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        }
    }
}
