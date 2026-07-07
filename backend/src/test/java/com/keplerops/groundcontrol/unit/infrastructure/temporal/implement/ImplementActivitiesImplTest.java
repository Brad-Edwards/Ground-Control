package com.keplerops.groundcontrol.unit.infrastructure.temporal.implement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.service.CreateTraceabilityLinkCommand;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementActivitiesImpl;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiState;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergedArtifactsInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergedArtifactsResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveRepositoryBindingInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.port.GitHubWorkflowPort;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.port.RepositoryBindingPort;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.port.SonarGatePort;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.port.WorkspacePort;
import io.temporal.failure.ApplicationFailure;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImplementActivitiesImplTest {

    private static final RepositoryBinding BINDING = new RepositoryBinding("acme", "repo", "dev");

    private GitHubWorkflowPort gitHub;
    private WorkspacePort workspace;
    private SonarGatePort sonar;
    private RepositoryBindingPort repositoryBinding;
    private RequirementService requirementService;
    private ProjectService projectService;
    private TraceabilityService traceabilityService;
    private ImplementActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        gitHub = mock(GitHubWorkflowPort.class);
        workspace = mock(WorkspacePort.class);
        sonar = mock(SonarGatePort.class);
        repositoryBinding = mock(RepositoryBindingPort.class);
        requirementService = mock(RequirementService.class);
        projectService = mock(ProjectService.class);
        traceabilityService = mock(TraceabilityService.class);
        activities = new ImplementActivitiesImpl(
                gitHub, workspace, sonar, repositoryBinding, requirementService, projectService, traceabilityService);
    }

    @Test
    void resolveRepositoryBindingResolvesThroughProjectScopedPort() {
        when(repositoryBinding.resolve("proj")).thenReturn(BINDING);

        RepositoryBinding result = activities.resolveRepositoryBinding(new ResolveRepositoryBindingInput("proj"));

        assertThat(result).isEqualTo(BINDING);
    }

    @Test
    void resolveRepositoryBindingRethrowsDomainFailureAsNonRetryable() {
        when(repositoryBinding.resolve("proj")).thenThrow(new DomainValidationException("unknown project"));
        ResolveRepositoryBindingInput input = new ResolveRepositoryBindingInput("proj");

        assertThatThrownBy(() -> activities.resolveRepositoryBinding(input))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(e ->
                        assertThat(((ApplicationFailure) e).isNonRetryable()).isTrue());
    }

    @Test
    void resolveIssueUsesProjectResolvedBinding() {
        when(gitHub.developBranch(BINDING, 42)).thenReturn("42-slug");

        ResolveIssueResult result = activities.resolveIssue(new ResolveIssueInput(BINDING, 42, List.of("GC-O009")));

        assertThat(result.branch()).isEqualTo("42-slug");
        assertThat(result.baseBranch()).isEqualTo("dev");
        assertThat(result.requirementUids()).containsExactly("GC-O009");
    }

    @Test
    void evaluateQualityGatePassesWhenAllFactsHold() {
        QualityGateResult result = activities.evaluateQualityGate(new QualityGateInput("proj", true, true, true));

        assertThat(result.passed()).isTrue();
        assertThat(result.failedGates()).isEmpty();
    }

    @Test
    void evaluateQualityGateReportsEachFailedGate() {
        QualityGateResult result = activities.evaluateQualityGate(new QualityGateInput("proj", false, false, true));

        assertThat(result.passed()).isFalse();
        assertThat(result.failedGates()).containsExactly("build", "changelog");
    }

    @Test
    void stageCommitPushDefaultsPrecommitRetriesToFive() {
        when(workspace.stageCommitPush("42-slug", "msg", 5, "key"))
                .thenReturn(new GitPublishResult("abc1234", true, 1));

        GitPublishResult result = activities.stageCommitPush(new GitPublishInput("42-slug", "msg", null, "key"));

        assertThat(result.pushed()).isTrue();
        verify(workspace).stageCommitPush("42-slug", "msg", 5, "key");
    }

    @Test
    void stageCommitPushClampsPrecommitRetriesToRange() {
        when(workspace.stageCommitPush(eq("b"), eq("m"), org.mockito.ArgumentMatchers.anyInt(), eq("k")))
                .thenReturn(new GitPublishResult("abc1234", true, 1));

        // Below 1 clamps up to 1; above MAX (5) clamps down to 5.
        activities.stageCommitPush(new GitPublishInput("b", "m", 0, "k"));
        activities.stageCommitPush(new GitPublishInput("b", "m", 100, "k"));

        verify(workspace).stageCommitPush("b", "m", 1, "k");
        verify(workspace).stageCommitPush("b", "m", 5, "k");
    }

    @Test
    void runCompletionGateDelegatesToWorkspace() {
        when(workspace.runCompletionGate("make check")).thenReturn(new CompletionGateResult(true, 0, "ok"));

        CompletionGateResult result = activities.runCompletionGate(new CompletionGateInput("make check"));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void observeMergedArtifactsClassifiesEveryTestPathBranch() {
        // Exercises all of isTestPath's branches: /test/, /tests/, endsWith Test.java, .test., .spec.,
        // and backslash normalization; plus code paths that must classify as IMPLEMENTS.
        when(gitHub.changedFiles(BINDING, 7))
                .thenReturn(List.of(
                        "backend/src/main/java/App.java", // code
                        "mcp/lib.js", // code
                        "backend/src/test/java/AppTest.java", // /test/
                        "tools/tests/helper.py", // /tests/
                        "backend/src/main/java/FooTest.java", // endsWith Test.java (no /test/)
                        "frontend/src/app.test.ts", // .test.
                        "frontend/src/app.spec.ts", // .spec.
                        "backend\\src\\test\\java\\Win.java")); // backslash-normalized /test/

        MergedArtifactsResult result = activities.observeMergedArtifacts(new MergedArtifactsInput(BINDING, 7));

        assertThat(result.implementsArtifacts()).containsExactly("backend/src/main/java/App.java", "mcp/lib.js");
        assertThat(result.testsArtifacts())
                .containsExactly(
                        "backend/src/test/java/AppTest.java",
                        "tools/tests/helper.py",
                        "backend/src/main/java/FooTest.java",
                        "frontend/src/app.test.ts",
                        "frontend/src/app.spec.ts",
                        "backend\\src\\test\\java\\Win.java");
    }

    @Test
    void transitionRequirementStatusTransitionsFromDraft() {
        UUID projectId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        Requirement draft = mock(Requirement.class);
        Requirement active = mock(Requirement.class);
        when(projectService.resolveProjectId("proj")).thenReturn(projectId);
        when(requirementService.getByUid(projectId, "GC-O009")).thenReturn(draft);
        when(draft.getStatus()).thenReturn(Status.DRAFT);
        when(draft.getId()).thenReturn(requirementId);
        when(requirementService.transitionStatus(requirementId, Status.ACTIVE)).thenReturn(active);
        when(active.getStatus()).thenReturn(Status.ACTIVE);

        StatusTransitionResult result =
                activities.transitionRequirementStatus(new StatusTransitionInput(Status.ACTIVE, "proj", "GC-O009"));

        assertThat(result.transitioned()).isTrue();
        assertThat(result.newStatus()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void transitionRequirementStatusIsNoopWhenAlreadyAtTarget() {
        UUID projectId = UUID.randomUUID();
        Requirement alreadyActive = mock(Requirement.class);
        when(projectService.resolveProjectId("proj")).thenReturn(projectId);
        when(requirementService.getByUid(projectId, "GC-O009")).thenReturn(alreadyActive);
        when(alreadyActive.getStatus()).thenReturn(Status.ACTIVE);

        StatusTransitionResult result =
                activities.transitionRequirementStatus(new StatusTransitionInput(Status.ACTIVE, "proj", "GC-O009"));

        assertThat(result.transitioned()).isFalse();
        verify(requirementService, never()).transitionStatus(any(), any());
    }

    @Test
    void transitionRequirementStatusRethrowsDomainFailureAsNonRetryable() {
        UUID projectId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        Requirement draft = mock(Requirement.class);
        when(projectService.resolveProjectId("proj")).thenReturn(projectId);
        when(requirementService.getByUid(projectId, "GC-O009")).thenReturn(draft);
        when(draft.getStatus()).thenReturn(Status.DRAFT);
        when(draft.getId()).thenReturn(requirementId);
        when(requirementService.transitionStatus(requirementId, Status.ACTIVE))
                .thenThrow(new DomainValidationException("illegal transition"));
        StatusTransitionInput input = new StatusTransitionInput(Status.ACTIVE, "proj", "GC-O009");

        assertThatThrownBy(() -> activities.transitionRequirementStatus(input))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(e -> {
                    ApplicationFailure failure = (ApplicationFailure) e;
                    assertThat(failure.isNonRetryable()).isTrue();
                    assertThat(failure.getType()).isEqualTo("requirement_status_transition");
                });
    }

    @Test
    void reconcileTraceabilityCreatesImplementsAndTestsLinks() {
        UUID projectId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        Requirement requirement = mock(Requirement.class);
        when(projectService.resolveProjectId("proj")).thenReturn(projectId);
        when(requirementService.getByUid(projectId, "GC-O009")).thenReturn(requirement);
        when(requirement.getId()).thenReturn(requirementId);
        when(traceabilityService.getLinksForRequirement(requirementId)).thenReturn(List.of());

        TraceabilityReconcileResult result = activities.reconcileTraceability(new TraceabilityReconcileInput(
                "proj", List.of("GC-O009"), 7, List.of("App.java", "Svc.java"), List.of("AppTest.java"), "key"));

        assertThat(result.implementsLinksCreated()).isEqualTo(2);
        assertThat(result.testsLinksCreated()).isEqualTo(1);
        verify(traceabilityService, times(3)).createLink(eq(requirementId), any(CreateTraceabilityLinkCommand.class));
    }

    @Test
    void reconcileTraceabilitySkipsExistingLinks() {
        UUID projectId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        Requirement requirement = mock(Requirement.class);
        var existing = mock(com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink.class);
        when(existing.getLinkType()).thenReturn(LinkType.IMPLEMENTS);
        when(existing.getArtifactIdentifier()).thenReturn("App.java");
        when(projectService.resolveProjectId("proj")).thenReturn(projectId);
        when(requirementService.getByUid(projectId, "GC-O009")).thenReturn(requirement);
        when(requirement.getId()).thenReturn(requirementId);
        when(traceabilityService.getLinksForRequirement(requirementId)).thenReturn(List.of(existing));

        // App.java is already linked (observe-before-create); only Svc.java should be created.
        TraceabilityReconcileResult result = activities.reconcileTraceability(new TraceabilityReconcileInput(
                "proj", List.of("GC-O009"), 7, List.of("App.java", "Svc.java"), List.of(), "key"));

        assertThat(result.implementsLinksCreated()).isEqualTo(1);
        verify(traceabilityService, times(1)).createLink(eq(requirementId), any(CreateTraceabilityLinkCommand.class));
    }

    @Test
    void reconcileTraceabilityUsesImplementsAndTestsLinkTypes() {
        UUID projectId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        Requirement requirement = mock(Requirement.class);
        when(projectService.resolveProjectId("proj")).thenReturn(projectId);
        when(requirementService.getByUid(projectId, "GC-O009")).thenReturn(requirement);
        when(requirement.getId()).thenReturn(requirementId);
        when(traceabilityService.getLinksForRequirement(requirementId)).thenReturn(List.of());

        activities.reconcileTraceability(new TraceabilityReconcileInput(
                "proj", List.of("GC-O009"), 7, List.of("App.java"), List.of("AppTest.java"), "key"));

        verify(traceabilityService)
                .createLink(
                        requirementId,
                        new CreateTraceabilityLinkCommand(
                                ArtifactType.CODE_FILE, "App.java", null, "App.java", LinkType.IMPLEMENTS));
        verify(traceabilityService)
                .createLink(
                        requirementId,
                        new CreateTraceabilityLinkCommand(
                                ArtifactType.TEST, "AppTest.java", null, "AppTest.java", LinkType.TESTS));
    }

    @Test
    void observeCiDelegatesToPort() {
        when(gitHub.observeCi(BINDING, 7)).thenReturn(new CiObservationResult(CiState.SUCCESS, List.of()));

        CiObservationResult result = activities.observeCi(new CiObservationInput(BINDING, 7));

        assertThat(result.state()).isEqualTo(CiState.SUCCESS);
    }
}
