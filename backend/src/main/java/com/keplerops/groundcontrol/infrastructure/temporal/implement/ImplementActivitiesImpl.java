package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import com.keplerops.groundcontrol.domain.exception.GroundControlException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.service.CreateTraceabilityLinkCommand;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CloseIssueInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CloseIssueResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergedArtifactsInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergedArtifactsResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.OpenPullRequestInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.OpenPullRequestResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveRepositoryBindingInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarStatus;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.port.GitHubWorkflowPort;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.port.RepositoryBindingPort;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.port.SonarGatePort;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.port.WorkspacePort;
import io.temporal.failure.ApplicationFailure;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Deterministic {@code /implement} activity implementations (GC-O009 phase 2). Pure API orchestration
 * over existing domain services ({@link RequirementService}, {@link TraceabilityService},
 * {@link ProjectService}) and infrastructure ports; no LLM provider dependency (ADR-028).
 *
 * <p>Privileged GitHub side effects are bound to a project-resolved {@link RepositoryBinding}
 * (never caller-supplied coordinates), and mutating activities carry an idempotency key so an
 * at-least-once retry does not duplicate a side effect; {@code reconcileTraceability} observes existing
 * links before creating any.
 *
 * <p>Not a Spring bean in phase 2: the port adapters (GitHub/git/Sonar/binding) are owned by later
 * program phases (#1278/#1279/#1281), so the class is constructed explicitly — by tests today, by the
 * worker configuration once the adapters land. Expected domain failures (validation / not-found / auth)
 * are rethrown as non-retryable {@link ApplicationFailure}s so Temporal does not retry a deterministic
 * rejection; transient port failures propagate and are retried under the activity's retry policy.
 */
public final class ImplementActivitiesImpl implements ImplementActivities {

    private static final int MAX_PRECOMMIT_RETRIES = 5;

    private final GitHubWorkflowPort gitHub;
    private final WorkspacePort workspace;
    private final SonarGatePort sonar;
    private final RepositoryBindingPort repositoryBinding;
    private final RequirementService requirementService;
    private final ProjectService projectService;
    private final TraceabilityService traceabilityService;

    public ImplementActivitiesImpl(
            GitHubWorkflowPort gitHub,
            WorkspacePort workspace,
            SonarGatePort sonar,
            RepositoryBindingPort repositoryBinding,
            RequirementService requirementService,
            ProjectService projectService,
            TraceabilityService traceabilityService) {
        this.gitHub = gitHub;
        this.workspace = workspace;
        this.sonar = sonar;
        this.repositoryBinding = repositoryBinding;
        this.requirementService = requirementService;
        this.projectService = projectService;
        this.traceabilityService = traceabilityService;
    }

    @Override
    public RepositoryBinding resolveRepositoryBinding(ResolveRepositoryBindingInput input) {
        return domainCall("repository_binding_resolution", () -> repositoryBinding.resolve(input.project()));
    }

    @Override
    public ResolveIssueResult resolveIssue(ResolveIssueInput input) {
        RepositoryBinding repository = input.repository();
        String branch = gitHub.developBranch(repository, input.issueNumber());
        return new ResolveIssueResult(input.issueNumber(), branch, repository.baseBranch(), input.requirementUids());
    }

    @Override
    public CompletionGateResult runCompletionGate(CompletionGateInput input) {
        return workspace.runCompletionGate(input.command());
    }

    @Override
    public QualityGateResult evaluateQualityGate(QualityGateInput input) {
        // GC-O007 Phase B hard completion gate: build success + CHANGELOG updated + clause mapping
        // complete. This is the /implement completion gate, distinct from project-configured metric
        // quality gates; it is computed here rather than weakened or reordered (acceptance criterion 4).
        List<String> failed = new ArrayList<>();
        if (!input.buildPassed()) {
            failed.add("build");
        }
        if (!input.changelogUpdated()) {
            failed.add("changelog");
        }
        if (!input.clauseMappingComplete()) {
            failed.add("clause-mapping");
        }
        return new QualityGateResult(failed.isEmpty(), failed);
    }

    @Override
    public GitPublishResult stageCommitPush(GitPublishInput input) {
        int retries = input.maxPrecommitRetries() == null
                ? MAX_PRECOMMIT_RETRIES
                : Math.min(Math.max(input.maxPrecommitRetries(), 1), MAX_PRECOMMIT_RETRIES);
        return workspace.stageCommitPush(input.branch(), input.commitMessage(), retries, input.idempotencyKey());
    }

    @Override
    public OpenPullRequestResult openPullRequest(OpenPullRequestInput input) {
        return gitHub.openPullRequest(input.repository(), input.headBranch(), input.title(), input.idempotencyKey());
    }

    @Override
    public CiObservationResult observeCi(CiObservationInput input) {
        return gitHub.observeCi(input.repository(), input.prNumber());
    }

    @Override
    public SonarGateResult evaluateSonarGate(SonarGateInput input) {
        SonarStatus status = sonar.fetchQualityGate(input.projectKey(), input.prNumber());
        return new SonarGateResult(status);
    }

    @Override
    public MergeObservationResult observeMergeState(MergeObservationInput input) {
        return gitHub.observeMerge(input.repository(), input.prNumber());
    }

    @Override
    public MergedArtifactsResult observeMergedArtifacts(MergedArtifactsInput input) {
        List<String> implementsArtifacts = new ArrayList<>();
        List<String> testsArtifacts = new ArrayList<>();
        for (String path : gitHub.changedFiles(input.repository(), input.prNumber())) {
            if (isTestPath(path)) {
                testsArtifacts.add(path);
            } else {
                implementsArtifacts.add(path);
            }
        }
        return new MergedArtifactsResult(implementsArtifacts, testsArtifacts);
    }

    private static boolean isTestPath(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.contains("/test/")
                || normalized.contains("/tests/")
                || normalized.endsWith("Test.java")
                || normalized.contains(".test.")
                || normalized.contains(".spec.");
    }

    @Override
    public StatusTransitionResult transitionRequirementStatus(StatusTransitionInput input) {
        return domainCall("requirement_status_transition", () -> {
            UUID projectId = projectService.resolveProjectId(input.project());
            Requirement requirement = requirementService.getByUid(projectId, input.requirementUid());
            // Observe-before-transition: a retry after the transition already landed is a no-op, so the
            // activity is idempotent under Temporal at-least-once execution.
            if (requirement.getStatus() == input.targetStatus()) {
                return new StatusTransitionResult(input.requirementUid(), requirement.getStatus(), false);
            }
            Requirement transitioned = requirementService.transitionStatus(requirement.getId(), input.targetStatus());
            return new StatusTransitionResult(input.requirementUid(), transitioned.getStatus(), true);
        });
    }

    @Override
    public TraceabilityReconcileResult reconcileTraceability(TraceabilityReconcileInput input) {
        return domainCall("traceability_reconcile", () -> {
            UUID projectId = projectService.resolveProjectId(input.project());
            int implementsCreated = 0;
            int testsCreated = 0;
            for (String uid : input.requirementUids()) {
                UUID requirementId = requirementService.getByUid(projectId, uid).getId();
                // Observe-before-create: reconciliation is idempotent under retry — only links that do
                // not already exist for this requirement are created, so a rerun never duplicates links.
                Set<String> existing = existingLinkKeys(requirementId);
                for (String artifact : input.implementsArtifacts()) {
                    if (createLinkIfAbsent(
                            requirementId, existing, ArtifactType.CODE_FILE, artifact, LinkType.IMPLEMENTS)) {
                        implementsCreated++;
                    }
                }
                for (String artifact : input.testsArtifacts()) {
                    if (createLinkIfAbsent(requirementId, existing, ArtifactType.TEST, artifact, LinkType.TESTS)) {
                        testsCreated++;
                    }
                }
            }
            return new TraceabilityReconcileResult(implementsCreated, testsCreated);
        });
    }

    private Set<String> existingLinkKeys(UUID requirementId) {
        Set<String> keys = new HashSet<>();
        for (TraceabilityLink link : traceabilityService.getLinksForRequirement(requirementId)) {
            keys.add(linkKey(link.getLinkType(), link.getArtifactIdentifier()));
        }
        return keys;
    }

    private boolean createLinkIfAbsent(
            UUID requirementId, Set<String> existing, ArtifactType artifactType, String artifact, LinkType linkType) {
        if (!existing.add(linkKey(linkType, artifact))) {
            return false;
        }
        traceabilityService.createLink(
                requirementId, new CreateTraceabilityLinkCommand(artifactType, artifact, null, artifact, linkType));
        return true;
    }

    private static String linkKey(LinkType linkType, String artifactIdentifier) {
        return linkType + ":" + artifactIdentifier;
    }

    @Override
    public CloseIssueResult closeIssue(CloseIssueInput input) {
        return gitHub.closeIssue(input.repository(), input.issueNumber(), input.idempotencyKey());
    }

    /**
     * Runs a domain-service call, translating an expected {@link GroundControlException} (validation,
     * not-found, authorization) into a non-retryable {@link ApplicationFailure}. Temporal must not
     * retry a deterministic domain rejection; only transient infrastructure failures (which propagate
     * unchanged) are retried under the activity's retry policy.
     */
    private static <T> T domainCall(String errorType, Supplier<T> call) {
        try {
            return call.get();
        } catch (GroundControlException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), errorType);
        }
    }
}
