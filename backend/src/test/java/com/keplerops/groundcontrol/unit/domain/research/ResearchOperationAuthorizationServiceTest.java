package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.AuthorizationException;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataForm;
import com.keplerops.groundcontrol.domain.research.model.ResearchDestinationClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchEgressAllowance;
import com.keplerops.groundcontrol.domain.research.model.ResearchHighRiskOperationKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchOperationAuthorizationState;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunOperationAuthorization;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunOperationAuthorizationRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import com.keplerops.groundcontrol.domain.research.service.DecideOperationAuthorizationCommand;
import com.keplerops.groundcontrol.domain.research.service.RequestOperationAuthorizationCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchOperationAuthorizationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * GC-RSCH-R005 / GC-RSCH-N005 / GC-RSCH-N006 / GC-RSCH-N014 / ADR-085 —
 * behavioral unit tests for {@link ResearchOperationAuthorizationService}: the
 * default-deny authorization lifecycle (propose, admin decide, one-time consume),
 * idempotency, tool-inventory gating, actor sourcing, and cross-scope concealment.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchOperationAuthorizationServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID AUTH_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");

    @Mock
    private ResearchRunRepository runRepository;

    @Mock
    private ResearchRunOperationAuthorizationRepository authorizationRepository;

    private ResearchOperationAuthorizationService service;
    private Project project;
    private ResearchRun run;

    @BeforeEach
    void setUp() {
        service = new ResearchOperationAuthorizationService(runRepository, authorizationRepository);
        project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        TestUtil.setField(project, "id", PROJECT_ID);
        run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        TestUtil.setField(run, "id", RUN_ID);
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(authorizationRepository.save(any())).thenAnswer(inv -> {
            ResearchRunOperationAuthorization a = inv.getArgument(0);
            if (a.getId() == null) {
                TestUtil.setField(a, "id", UUID.randomUUID());
            }
            return a;
        });
        ActorHolder.set("admin@keplerops");
    }

    @AfterEach
    void tearDown() {
        ActorHolder.clear();
    }

    private RequestOperationAuthorizationCommand request(
            ResearchDataClass dataClass, ResearchDestinationClass destination, ResearchDataForm form, String tool) {
        return new RequestOperationAuthorizationCommand(
                ResearchHighRiskOperationKind.EXTERNAL_WRITE,
                dataClass,
                destination,
                form,
                tool,
                "sandbox-default",
                "repo",
                null,
                "external write to VCS",
                "src-" + tool);
    }

    @Test
    void requestPersistsProposedWithDefaultDenyBasisAndServerActor() {
        run.setAllowedTools(List.of("scholarly"));
        var saved = service.requestAuthorization(
                PROJECT_ID,
                RUN_ID,
                request(
                        ResearchDataClass.CONFIDENTIAL,
                        ResearchDestinationClass.AI_PROVIDER,
                        ResearchDataForm.SUMMARY,
                        "scholarly"));

        assertThat(saved.getState()).isEqualTo(ResearchOperationAuthorizationState.PROPOSED);
        assertThat(saved.getPolicyBasis()).isEqualTo("default_deny");
        assertThat(saved.getProposingActor()).isEqualTo("admin@keplerops");
    }

    @Test
    void requestRejectsMissingConcreteEffectIdentity() {
        // ADR-085 §1: a null/blank toolId (adapter identity) is rejected so the
        // record always binds a concrete effect and never skips inventory.
        var cmd = new RequestOperationAuthorizationCommand(
                ResearchHighRiskOperationKind.EXTERNAL_WRITE,
                ResearchDataClass.PUBLIC,
                ResearchDestinationClass.AI_PROVIDER,
                ResearchDataForm.SUMMARY,
                null, // toolId missing
                "sandbox-default",
                null,
                null,
                "summary",
                "src-1");
        assertThatThrownBy(() -> service.requestAuthorization(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void requestReplaysExistingRecordForSameSourceActionId() {
        var existing = new ResearchRunOperationAuthorization(
                run,
                ResearchHighRiskOperationKind.EXTERNAL_WRITE,
                ResearchDataClass.CONFIDENTIAL,
                ResearchDestinationClass.AI_PROVIDER,
                ResearchDataForm.SUMMARY);
        existing.setToolId("scholarly");
        existing.setSandboxProfile("sandbox-default");
        TestUtil.setField(existing, "id", AUTH_ID);
        when(authorizationRepository.findByResearchRunIdAndSourceActionId(RUN_ID, "act-1"))
                .thenReturn(Optional.of(existing));

        var cmd = new RequestOperationAuthorizationCommand(
                ResearchHighRiskOperationKind.EXTERNAL_WRITE,
                ResearchDataClass.CONFIDENTIAL,
                ResearchDestinationClass.AI_PROVIDER,
                ResearchDataForm.SUMMARY,
                "scholarly",
                "sandbox-default",
                null,
                null,
                "external write",
                "act-1");

        var result = service.requestAuthorization(PROJECT_ID, RUN_ID, cmd);

        assertThat(result).isSameAs(existing);
        verify(authorizationRepository, never()).save(any());
    }

    @Test
    void requestRejectsReusedSourceActionIdWithDifferentPayload() {
        var existing = new ResearchRunOperationAuthorization(
                run,
                ResearchHighRiskOperationKind.EXTERNAL_WRITE,
                ResearchDataClass.CONFIDENTIAL,
                ResearchDestinationClass.AI_PROVIDER,
                ResearchDataForm.SUMMARY);
        when(authorizationRepository.findByResearchRunIdAndSourceActionId(RUN_ID, "act-1"))
                .thenReturn(Optional.of(existing));

        var cmd = new RequestOperationAuthorizationCommand(
                ResearchHighRiskOperationKind.BROWSER_ACTIVITY,
                ResearchDataClass.PUBLIC,
                ResearchDestinationClass.BROWSER_TARGET,
                ResearchDataForm.NONE,
                "scholarly",
                "sandbox-default",
                null,
                null,
                "browse",
                "act-1");

        assertThatThrownBy(() -> service.requestAuthorization(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void requestRejectsToolNotInRunInventory() {
        run.setAllowedTools(List.of("scholarly-search"));
        var cmd = request(
                ResearchDataClass.PUBLIC, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY, "shell-exec");
        assertThatThrownBy(() -> service.requestAuthorization(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void requestRejectsMissingRequiredEnums() {
        var cmd = new RequestOperationAuthorizationCommand(null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.requestAuthorization(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void approveIsRejectedWhenRunPolicyDoesNotPermit() {
        // run has an empty egress policy -> default deny
        var record = proposed(
                ResearchDataClass.CONFIDENTIAL, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.RAW_CONTENT);
        when(authorizationRepository.findByIdAndResearchRunId(AUTH_ID, RUN_ID)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.decideAuthorization(
                        PROJECT_ID, RUN_ID, AUTH_ID, new DecideOperationAuthorizationCommand(true, null)))
                .isInstanceOf(AuthorizationException.class);
        assertThat(record.getState()).isEqualTo(ResearchOperationAuthorizationState.PROPOSED);
    }

    @Test
    void approveSucceedsWhenRunPolicyPermitsAndActorPresent() {
        run.setEgressPolicy(List.of(new ResearchEgressAllowance(
                ResearchDataClass.CONFIDENTIAL, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY, null)));
        var record = proposed(
                ResearchDataClass.CONFIDENTIAL, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY);
        when(authorizationRepository.findByIdAndResearchRunId(AUTH_ID, RUN_ID)).thenReturn(Optional.of(record));

        var decided = service.decideAuthorization(
                PROJECT_ID, RUN_ID, AUTH_ID, new DecideOperationAuthorizationCommand(true, "ok"));

        assertThat(decided.getState()).isEqualTo(ResearchOperationAuthorizationState.APPROVED);
        assertThat(decided.getDecidingActor()).isEqualTo("admin@keplerops");
    }

    @Test
    void approveRequiresAnAuthenticatedActor() {
        ActorHolder.clear(); // an AUTONOMOUS run carries no authenticated approver
        run.setEgressPolicy(List.of(new ResearchEgressAllowance(
                ResearchDataClass.PUBLIC, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY, null)));
        var record = proposed(ResearchDataClass.PUBLIC, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY);
        when(authorizationRepository.findByIdAndResearchRunId(AUTH_ID, RUN_ID)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.decideAuthorization(
                        PROJECT_ID, RUN_ID, AUTH_ID, new DecideOperationAuthorizationCommand(true, null)))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void denyMovesRecordToDenied() {
        var record = proposed(ResearchDataClass.PUBLIC, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY);
        when(authorizationRepository.findByIdAndResearchRunId(AUTH_ID, RUN_ID)).thenReturn(Optional.of(record));

        var decided = service.decideAuthorization(
                PROJECT_ID, RUN_ID, AUTH_ID, new DecideOperationAuthorizationCommand(false, "no"));

        assertThat(decided.getState()).isEqualTo(ResearchOperationAuthorizationState.DENIED);
    }

    @Test
    void consumeSpendsApprovedRecordExactlyOnce() {
        var record = proposed(ResearchDataClass.PUBLIC, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY);
        record.approve("admin@keplerops", "allow");
        when(authorizationRepository.findByIdAndResearchRunId(AUTH_ID, RUN_ID)).thenReturn(Optional.of(record));

        var consumed = service.consumeAuthorization(PROJECT_ID, RUN_ID, AUTH_ID);
        assertThat(consumed.getState()).isEqualTo(ResearchOperationAuthorizationState.CONSUMED);

        assertThatThrownBy(() -> service.consumeAuthorization(PROJECT_ID, RUN_ID, AUTH_ID))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void consumeRejectsExpiredApproval() {
        var record = proposed(ResearchDataClass.PUBLIC, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY);
        record.approve("admin@keplerops", "allow");
        record.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(authorizationRepository.findByIdAndResearchRunId(AUTH_ID, RUN_ID)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.consumeAuthorization(PROJECT_ID, RUN_ID, AUTH_ID))
                .isInstanceOf(DomainValidationException.class);
        assertThat(record.getState()).isEqualTo(ResearchOperationAuthorizationState.EXPIRED);
    }

    @Test
    void crossProjectRunIsConcealedAsNotFound() {
        var otherProject = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(runRepository.findByIdAndProjectId(RUN_ID, otherProject)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listAuthorizations(otherProject, RUN_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void missingAuthorizationIsConcealedAsNotFound() {
        when(authorizationRepository.findByIdAndResearchRunId(AUTH_ID, RUN_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAuthorization(PROJECT_ID, RUN_ID, AUTH_ID))
                .isInstanceOf(NotFoundException.class);
    }

    private ResearchRunOperationAuthorization proposed(
            ResearchDataClass dataClass, ResearchDestinationClass destination, ResearchDataForm form) {
        var record = new ResearchRunOperationAuthorization(
                run, ResearchHighRiskOperationKind.EXTERNAL_WRITE, dataClass, destination, form);
        TestUtil.setField(record, "id", AUTH_ID);
        return record;
    }
}
