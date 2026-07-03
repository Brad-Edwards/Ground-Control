package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.AuthorizationException;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunOperationAuthorization;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunOperationAuthorizationRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-RSCH-R005 / GC-RSCH-N005 / GC-RSCH-N006 / GC-RSCH-N014 / ADR-086 —
 * application service that authorizes and records research high-risk operations.
 * It is the sole authority for authorization write legality; it never executes an
 * operation (execution is an adapter/orchestrator concern per ADR-086 §4).
 *
 * <p>Every lookup is project- and run-scoped, and a cross-project/cross-run
 * reference is concealed as {@link NotFoundException} so a probing caller cannot
 * learn another project's runs/records exist. Policy decisions are default-deny
 * and computed only from the run's snapshotted egress policy and allowed-tool
 * inventory over closed enums — retrieved/untrusted content can never influence a
 * decision (GC-RSCH-N014). The proposing/deciding actor comes from the
 * authenticated server context, never the caller; logs carry IDs/enums only.
 */
@Service
@Transactional
public class ResearchOperationAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(ResearchOperationAuthorizationService.class);

    private static final int TOOL_ID_MAX = 200;
    private static final int SANDBOX_PROFILE_MAX = 120;
    private static final int TARGET_CLASS_MAX = 120;
    private static final int SUMMARY_MAX = 2000;
    private static final int ACTION_ID_MAX = 200;

    private static final String INVALID = "invalid_operation_authorization";
    private static final String FIELD = "field";
    private static final String TOOL_ID_FIELD = "toolId";
    private static final String SOURCE_ACTION_ID_FIELD = "sourceActionId";

    private final ResearchRunRepository runRepository;
    private final ResearchRunOperationAuthorizationRepository authorizationRepository;

    public ResearchOperationAuthorizationService(
            ResearchRunRepository runRepository, ResearchRunOperationAuthorizationRepository authorizationRepository) {
        this.runRepository = runRepository;
        this.authorizationRepository = authorizationRepository;
    }

    /**
     * Propose a high-risk operation authorization. Idempotent on a run-scoped
     * {@code sourceActionId}. The authorization lands {@code PROPOSED} with a
     * default-deny policy basis computed from the run snapshot; it is never
     * auto-approved.
     */
    public ResearchRunOperationAuthorization requestAuthorization(
            UUID projectId, UUID runId, RequestOperationAuthorizationCommand command) {
        var run = requireRun(projectId, runId);
        validateRequest(command);
        var sourceActionId = emptyToNull(command.sourceActionId());
        if (sourceActionId != null) {
            var existing = authorizationRepository.findByResearchRunIdAndSourceActionId(runId, sourceActionId);
            if (existing.isPresent()) {
                if (!equivalent(existing.get(), command)) {
                    throw new ConflictException(
                            "Source action id reused with a different authorization payload",
                            "operation_authorization_idempotency_conflict",
                            Map.of(FIELD, SOURCE_ACTION_ID_FIELD));
                }
                return existing.get();
            }
        }
        requireToolInInventory(run, command.toolId());

        var authorization = new ResearchRunOperationAuthorization(
                run, command.operationKind(), command.dataClass(), command.destinationClass(), command.requestedForm());
        authorization.setToolId(emptyToNull(command.toolId()));
        authorization.setSandboxProfile(emptyToNull(command.sandboxProfile()));
        authorization.setTargetClass(emptyToNull(command.targetClass()));
        authorization.setSummary(emptyToNull(command.summary()));
        authorization.setSourceActionId(sourceActionId);
        authorization.setExpiresAt(command.expiresAt());
        authorization.setProposingActor(currentActor());
        var decision = EgressPolicyEvaluator.evaluate(
                run.getEgressPolicy(), command.dataClass(), command.destinationClass(), command.requestedForm());
        authorization.setPolicyBasis(decision.basis());

        var saved = authorizationRepository.save(authorization);
        log.info(
                "research_operation_authorization_proposed: project={} run={} kind={} destination={} form={} egressPermitted={}",
                run.getProject().getIdentifier(),
                runId,
                command.operationKind(),
                command.destinationClass(),
                command.requestedForm(),
                decision.permitted());
        return saved;
    }

    /**
     * Approve or deny a proposed authorization. Approval requires an authenticated
     * deciding actor (an {@code AUTONOMOUS} run cannot self-approve — ADR-086 §3;
     * the REST route is admin-gated) AND the run's snapshotted egress policy to
     * permit the (dataClass, destination, form) tuple (default-deny otherwise).
     */
    public ResearchRunOperationAuthorization decideAuthorization(
            UUID projectId, UUID runId, UUID authorizationId, DecideOperationAuthorizationCommand command) {
        var run = requireRun(projectId, runId);
        var authorization = requireAuthorization(runId, authorizationId);
        var actor = currentActor();
        if (command.approve()) {
            if (actor == null) {
                throw new AuthorizationException("Approving a high-risk operation requires an authenticated actor");
            }
            var decision = EgressPolicyEvaluator.evaluate(
                    run.getEgressPolicy(),
                    authorization.getDataClass(),
                    authorization.getDestinationClass(),
                    authorization.getRequestedForm());
            if (!decision.permitted()) {
                throw new AuthorizationException(
                        "Run egress policy does not permit this operation (default deny): " + decision.basis());
            }
            authorization.approve(actor, decision.basis());
        } else {
            authorization.deny(actor, "denied");
        }
        var saved = authorizationRepository.save(authorization);
        log.info(
                "research_operation_authorization_decided: project={} run={} id={} approve={} state={}",
                run.getProject().getIdentifier(),
                runId,
                authorizationId,
                command.approve(),
                saved.getState());
        return saved;
    }

    /** Spend a one-time-use approved authorization (executor-side). Expired approvals are rejected. */
    public ResearchRunOperationAuthorization consumeAuthorization(UUID projectId, UUID runId, UUID authorizationId) {
        var run = requireRun(projectId, runId);
        var authorization = requireAuthorization(runId, authorizationId);
        authorization.consume(Instant.now());
        var saved = authorizationRepository.save(authorization);
        log.info(
                "research_operation_authorization_consumed: project={} run={} id={} state={}",
                run.getProject().getIdentifier(),
                runId,
                authorizationId,
                saved.getState());
        return saved;
    }

    public List<ResearchRunOperationAuthorization> listAuthorizations(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return authorizationRepository.findByResearchRunIdOrderByCreatedAtAsc(runId);
    }

    public ResearchRunOperationAuthorization getAuthorization(UUID projectId, UUID runId, UUID authorizationId) {
        requireRun(projectId, runId);
        return requireAuthorization(runId, authorizationId);
    }

    private void validateRequest(RequestOperationAuthorizationCommand command) {
        if (command == null
                || command.operationKind() == null
                || command.dataClass() == null
                || command.destinationClass() == null
                || command.requestedForm() == null) {
            throw new DomainValidationException(
                    "operationKind, dataClass, destinationClass, and requestedForm are required", INVALID, Map.of());
        }
        // ADR-086 §1: the authorization must bind a concrete effect request — the
        // adapter/tool identity, sandbox profile, bounded action summary, and a
        // retry-safe source-action id are all required so an executor can prove
        // which adapter/action/sandbox was authorized (and so a tool-less request
        // can never sidestep the allowed-tool inventory check).
        requireNotBlank(command.toolId(), TOOL_ID_FIELD);
        requireNotBlank(command.sandboxProfile(), "sandboxProfile");
        requireNotBlank(command.summary(), "summary");
        requireNotBlank(command.sourceActionId(), SOURCE_ACTION_ID_FIELD);
        requireUnder(command.toolId(), TOOL_ID_MAX, TOOL_ID_FIELD);
        requireUnder(command.sandboxProfile(), SANDBOX_PROFILE_MAX, "sandboxProfile");
        requireUnder(command.targetClass(), TARGET_CLASS_MAX, "targetClass");
        requireUnder(command.summary(), SUMMARY_MAX, "summary");
        requireUnder(command.sourceActionId(), ACTION_ID_MAX, SOURCE_ACTION_ID_FIELD);
    }

    /**
     * {@code allowedTools} is the run's declared tool inventory (ADR-086 §2): a
     * tool that is not in inventory may not even be requested. A null tool id is a
     * tool-less operation and skips the check.
     */
    private void requireToolInInventory(ResearchRun run, String toolId) {
        var tool = emptyToNull(toolId);
        if (tool == null) {
            return;
        }
        var allowed = run.getAllowedTools();
        if (allowed == null || !allowed.contains(tool)) {
            throw new DomainValidationException(
                    "Tool is not in the run's allowed-tool inventory", INVALID, Map.of(FIELD, TOOL_ID_FIELD));
        }
    }

    private boolean equivalent(
            ResearchRunOperationAuthorization existing, RequestOperationAuthorizationCommand command) {
        return existing.getOperationKind() == command.operationKind()
                && existing.getDataClass() == command.dataClass()
                && existing.getDestinationClass() == command.destinationClass()
                && existing.getRequestedForm() == command.requestedForm()
                && Objects.equals(existing.getToolId(), emptyToNull(command.toolId()))
                && Objects.equals(existing.getSandboxProfile(), emptyToNull(command.sandboxProfile()))
                && Objects.equals(existing.getTargetClass(), emptyToNull(command.targetClass()));
    }

    private ResearchRun requireRun(UUID projectId, UUID runId) {
        return runRepository
                .findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> new NotFoundException("Research run not found: " + runId));
    }

    private ResearchRunOperationAuthorization requireAuthorization(UUID runId, UUID authorizationId) {
        return authorizationRepository
                .findByIdAndResearchRunId(authorizationId, runId)
                .orElseThrow(() -> new NotFoundException("Operation authorization not found: " + authorizationId));
    }

    private void requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("Field " + field + " is required", INVALID, Map.of(FIELD, field));
        }
    }

    private void requireUnder(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new DomainValidationException(
                    "Field " + field + " exceeds max length", INVALID, Map.of(FIELD, field, "max", max));
        }
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String currentActor() {
        return emptyToNull(ActorHolder.get());
    }
}
