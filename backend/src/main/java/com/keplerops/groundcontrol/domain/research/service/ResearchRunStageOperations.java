package com.keplerops.groundcontrol.domain.research.service;

import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.AUTONOMOUS_DEFAULT_BASIS;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.CURRENT_STAGE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.GATE_POINT;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.INVALID_CODE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.TARGET_STAGE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.UID_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.currentActor;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.log;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireActive;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ProtocolCoverageDisposition;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanCoverage;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGate;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanCoverageRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchIntakeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateDecisionLogRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Run start and stage advancement (ADR-064).
 *
 * Split out of {@link ResearchRunService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class ResearchRunStageOperations {

    private final ResearchRunRepository runRepository;
    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunGateRepository gateRepository;
    private final ResearchRunGateDecisionLogRepository decisionLogRepository;
    private final ResearchIntakeRepository intakeRepository;
    private final ProjectService projectService;
    private final ProtocolPlanRepository protocolPlanRepository;
    private final ProtocolPlanCoverageRepository protocolPlanCoverageRepository;
    private final ResearchRunService service;

    @SuppressWarnings("java:S107") // aggregates the run repositories from one place on purpose
    ResearchRunStageOperations(
            ResearchRunRepository runRepository,
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunGateRepository gateRepository,
            ResearchRunGateDecisionLogRepository decisionLogRepository,
            ResearchIntakeRepository intakeRepository,
            ProjectService projectService,
            ProtocolPlanRepository protocolPlanRepository,
            ProtocolPlanCoverageRepository protocolPlanCoverageRepository,
            ResearchRunService service) {
        this.service = service;
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.gateRepository = gateRepository;
        this.decisionLogRepository = decisionLogRepository;
        this.intakeRepository = intakeRepository;
        this.projectService = projectService;
        this.protocolPlanRepository = protocolPlanRepository;
        this.protocolPlanCoverageRepository = protocolPlanCoverageRepository;
    }

    // ------------------------------------------------------------------
    // Lifecycle: start
    // ------------------------------------------------------------------

    /** GC-RSCH-R001/R003 — start a run, snapshot intake, resolve the gate policy. */
    ResearchRun start(StartResearchRunCommand command) {
        if (command == null) {
            throw new DomainValidationException("Start command must not be null", "research_run_required", Map.of());
        }
        var project = projectService.getById(command.projectId());
        if (project.getType() != ProjectType.RESEARCH) {
            throw new DomainValidationException(
                    "Research runs can only be started for RESEARCH projects",
                    "research_run_project_type_mismatch",
                    Map.of("project_type", project.getType().name()));
        }
        var uid = requireUid(command.uid());
        if (runRepository.existsByProjectIdAndUid(project.getId(), uid)) {
            throw new ConflictException("Research run with UID " + uid + " already exists in this project");
        }
        var intake = intakeRepository.findByProjectId(project.getId());
        var autonomy = command.autonomyLevel() != null
                ? command.autonomyLevel()
                : intake.map(i -> i.getAutonomyLevel()).orElse(null);
        if (autonomy == null) {
            throw new DomainValidationException(
                    "autonomyLevel is required when the project has no research intake to snapshot",
                    INVALID_CODE,
                    Map.of(FIELD, "autonomyLevel"));
        }

        var run = new ResearchRun(project, uid, autonomy);
        run.setIntendedOutput(
                command.intendedOutput() != null
                        ? command.intendedOutput()
                        : intake.map(i -> i.getIntendedOutput()).orElse(null));
        run.setOwnerActor(currentActor());
        intake.ifPresent(i -> {
            run.setBudgetTokens(i.getBudgetTokens());
            run.setBudgetWallClockMinutes(i.getBudgetWallClockMinutes());
            run.setBudgetCostUsdMicros(i.getBudgetCostUsdMicros());
            // Snapshot the high-risk operation policy at start so later intake
            // edits never re-authorize an active run (GC-RSCH-R005 / ADR-086 §2).
            run.setAllowedTools(i.getAllowedTools());
            run.setPrivacyConstraints(i.getPrivacyConstraints());
            run.setEgressPolicy(i.getEgressPolicy());
        });
        var saved = runRepository.save(run);

        var overrides = command.gateOverrides() != null
                ? command.gateOverrides()
                : Map.<ResearchGatePoint, ResearchGateBehavior>of();
        for (var gatePoint : ResearchGatePoint.values()) {
            var override = overrides.get(gatePoint);
            var behavior = resolveGateBehavior(autonomy, override);
            var basis = override != null ? "OVERRIDE" : "AUTONOMY:" + autonomy.name();
            gateRepository.save(new ResearchRunGate(saved, gatePoint, behavior, basis));
        }

        log.info(
                "research_run_started: project={} run={} uid={} autonomy={} stage={}",
                project.getIdentifier(),
                saved.getId(),
                uid,
                autonomy,
                saved.getCurrentStage());
        return saved;
    }

    private ResearchGateBehavior resolveGateBehavior(AutonomyLevel autonomy, ResearchGateBehavior override) {
        if (override != null) {
            return override;
        }
        return autonomy == AutonomyLevel.AUTONOMOUS
                ? ResearchGateBehavior.AUTONOMOUS_DEFAULT
                : ResearchGateBehavior.REQUIRE_HUMAN;
    }

    // ------------------------------------------------------------------
    // Lifecycle: stage advance (prerequisite + gate enforcement)
    // ------------------------------------------------------------------

    /**
     * GC-RSCH-F003 — advance the run into {@code targetStage}, which must be the
     * immediate next stage. The current stage's output artifact must be present
     * and ACTIVE (else a validation error, AC2), and the guarding gate must
     * permit the exit (else a conflict). Idempotent: advancing to a stage already
     * reached is a no-op.
     */
    ResearchRun advanceStage(UUID projectId, UUID runId, AdvanceStageCommand command) {
        var run = service.requireRun(projectId, runId);
        var targetStage = command == null ? null : command.targetStage();
        if (targetStage == null) {
            throw new DomainValidationException(
                    "targetStage must not be null", INVALID_CODE, Map.of(FIELD, TARGET_STAGE));
        }
        if (run.getCurrentStage().isAtOrAfter(targetStage)) {
            return run; // already at or past the target — idempotent no-op
        }
        requireActive(run);
        var next = run.getCurrentStage()
                .next()
                .orElseThrow(() -> new DomainValidationException(
                        "Run is already at the final stage", "research_run_no_next_stage", Map.of()));
        if (targetStage != next) {
            throw new DomainValidationException(
                    "targetStage " + targetStage + " is not the next stage after " + run.getCurrentStage(),
                    "research_run_stage_not_sequential",
                    Map.of(CURRENT_STAGE, run.getCurrentStage().name(), "next_stage", next.name()));
        }

        var requiredArtifact = run.getCurrentStage().outputArtifactType();
        var active = artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                runId, requiredArtifact, ResearchArtifactStatus.ACTIVE);
        if (active.isEmpty()) {
            throw new DomainValidationException(
                    "Cannot start " + next + ": required artifact " + requiredArtifact + " for stage "
                            + run.getCurrentStage() + " is missing",
                    "research_run_stage_blocked",
                    Map.of(CURRENT_STAGE, run.getCurrentStage().name(), "missing_artifact", requiredArtifact.name()));
        }

        // GC-RSCH-F008 / ADR-083 §2 — the SOURCE_SEARCH durable gate: an active
        // PROTOCOL_PLAN artifact is not enough on its own. The structured protocol
        // plan behind it must exist and have no unresolved BLOCKING_DECISION_REQUIRED
        // coverage, or search execution stays blocked regardless of caller.
        if (run.getCurrentStage() == ResearchRunStage.PROTOCOL_PLANNING) {
            requireProtocolPlanNotBlocking(active.get());
        }

        ResearchGatePoint.forStageExit(run.getCurrentStage()).ifPresent(point -> {
            var gate = gateRepository
                    .findByResearchRunIdAndGatePoint(runId, point)
                    .orElseThrow(() -> new NotFoundException("Gate " + point + " not found for run " + runId));
            if (gate.getBehavior() == ResearchGateBehavior.AUTONOMOUS_DEFAULT
                    && gate.getStatus() == ResearchGateStatus.PENDING) {
                gate.resolve(
                        ResearchGateDecisionOutcome.AUTO_ACCEPTED,
                        null,
                        "autonomous default accepted at stage advance",
                        run.getOwnerActor());
                gateRepository.save(gate);
                appendAutonomousDefaultDecisionLog(run, point);
            }
            if (!gate.permitsAdvance()) {
                throw new ConflictException(
                        "Gate " + point + " must be resolved before advancing past " + run.getCurrentStage(),
                        "research_run_gate_pending",
                        Map.of(
                                GATE_POINT,
                                point.name(),
                                "gate_status",
                                gate.getStatus().name()));
            }
        });

        run.advanceToStage(next);
        runRepository.save(run);
        log.info(
                "research_run_stage_advanced: project={} run={} to_stage={}",
                run.getProject().getIdentifier(),
                runId,
                next);
        return run;
    }

    private void appendAutonomousDefaultDecisionLog(ResearchRun run, ResearchGatePoint gatePoint) {
        var entry = new ResearchRunGateDecisionLog(
                run,
                gatePoint,
                gatePoint.guardedStageExit(),
                ResearchGateDecisionOutcome.AUTO_ACCEPTED,
                run.getOwnerActor(),
                Instant.now());
        entry.setArtifactAttemptNo(service.activeAttemptForGate(run, gatePoint));
        entry.setPolicyBasis(AUTONOMOUS_DEFAULT_BASIS);
        decisionLogRepository.save(entry);
        log.info(
                "research_run_decision_logged: run={} gate={} outcome={} basis={}",
                run.getId(),
                gatePoint,
                ResearchGateDecisionOutcome.AUTO_ACCEPTED,
                AUTONOMOUS_DEFAULT_BASIS);
    }

    /**
     * GC-RSCH-F008 / ADR-083 §2 — the {@code SOURCE_SEARCH} durable gate: an
     * active {@code PROTOCOL_PLAN} artifact is not enough on its own. The
     * structured protocol plan behind it must exist and carry no unresolved
     * {@code BLOCKING_DECISION_REQUIRED} coverage, or advancing past {@code
     * PROTOCOL_PLANNING} is rejected regardless of caller (closes the bypass a
     * caller could otherwise reach by invoking a lower-level action directly).
     */
    private void requireProtocolPlanNotBlocking(ResearchRunArtifact protocolPlanArtifact) {
        var plan = protocolPlanRepository.findByArtifactId(protocolPlanArtifact.getId());
        if (plan.isEmpty()) {
            throw new DomainValidationException(
                    "Cannot start SOURCE_SEARCH: no protocol plan has been recorded for the active PROTOCOL_PLAN"
                            + " artifact",
                    "research_run_protocol_plan_blocking",
                    Map.of());
        }
        var blocking = protocolPlanCoverageRepository
                .findByProtocolPlanId(plan.get().getId())
                .stream()
                .filter(c -> c.getDisposition() == ProtocolCoverageDisposition.BLOCKING_DECISION_REQUIRED)
                .map(ProtocolPlanCoverage::getContractEntryKey)
                .toList();
        if (!blocking.isEmpty()) {
            throw new DomainValidationException(
                    "Cannot start SOURCE_SEARCH: protocol plan has unresolved BLOCKING_DECISION_REQUIRED coverage",
                    "research_run_protocol_plan_blocking",
                    Map.of("blocking_entry_keys", String.join(",", blocking)));
        }
    }

    private String requireUid(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new DomainValidationException("uid must not be blank", INVALID_CODE, Map.of(FIELD, "uid"));
        }
        var trimmed = uid.trim();
        if (trimmed.length() > UID_MAX) {
            throw new DomainValidationException(
                    "uid exceeds max length", INVALID_CODE, Map.of(FIELD, "uid", "max", UID_MAX));
        }
        return trimmed;
    }
}
