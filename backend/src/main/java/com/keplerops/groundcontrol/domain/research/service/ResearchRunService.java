package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGate;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.ResearchIntakeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-RSCH-R001/R003/F003/F036/N007/N011 — application service for the {@link
 * ResearchRun} aggregate (ADR-063 / ADR-064).
 *
 * <p>Sole authority for the stage-transition graph, the prerequisite-artifact
 * matrix, gate-policy resolution, and idempotent checkpoint/resume. Controllers
 * and MCP handlers never re-implement this logic. Every lookup is project-scoped
 * and a cross-project reference is concealed as {@link NotFoundException} so a
 * probing caller cannot learn another project's runs exist (GC-RS-009).
 *
 * <p>Persisted records carry bounded, low-cardinality metadata only; the service
 * validates lengths and logs only IDs/enums — never prompts, manuscripts, search
 * results, secrets, or absolute workspace paths.
 */
@Service
@Transactional
public class ResearchRunService {

    private static final Logger log = LoggerFactory.getLogger(ResearchRunService.class);

    private static final int UID_MAX = 50;
    private static final int LOCATOR_MAX = 500;
    private static final int HASH_MAX = 128;
    private static final int IDEMPOTENCY_KEY_MAX = 200;
    private static final int OPTION_ID_MAX = 200;
    private static final int RATIONALE_MAX = 1000;
    private static final int ERROR_CODE_MAX = 100;
    private static final int ERROR_CLASS_MAX = 40;
    private static final int ERROR_SUMMARY_MAX = 500;

    private static final String INVALID_CODE = "research_run_invalid";
    private static final String FIELD = "field";

    private final ResearchRunRepository runRepository;
    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunGateRepository gateRepository;
    private final ResearchIntakeRepository intakeRepository;
    private final ProjectService projectService;

    public ResearchRunService(
            ResearchRunRepository runRepository,
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunGateRepository gateRepository,
            ResearchIntakeRepository intakeRepository,
            ProjectService projectService) {
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.gateRepository = gateRepository;
        this.intakeRepository = intakeRepository;
        this.projectService = projectService;
    }

    // ------------------------------------------------------------------
    // Lifecycle: start
    // ------------------------------------------------------------------

    /** GC-RSCH-R001/R003 — start a run, snapshot intake, resolve the gate policy. */
    public ResearchRun start(StartResearchRunCommand command) {
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
    // Lifecycle: artifacts (checkpoint authority)
    // ------------------------------------------------------------------

    /**
     * GC-RSCH-F003/F036 — record (or rework) the current stage's output artifact.
     * Idempotent on {@code idempotencyKey}; a rework supersedes the prior ACTIVE
     * record and re-opens the stage's guarding gate.
     */
    public ResearchRunArtifact recordArtifact(UUID projectId, UUID runId, RecordArtifactCommand command) {
        var run = requireRun(projectId, runId);
        requireActive(run);
        if (command == null || command.artifactType() == null) {
            throw new DomainValidationException(
                    "artifactType must not be null", INVALID_CODE, Map.of(FIELD, "artifactType"));
        }
        var expected = run.getCurrentStage().outputArtifactType();
        if (command.artifactType() != expected) {
            throw new DomainValidationException(
                    "Artifact type " + command.artifactType() + " does not match current stage "
                            + run.getCurrentStage(),
                    "research_run_artifact_stage_mismatch",
                    Map.of("current_stage", run.getCurrentStage().name(), "expected", expected.name()));
        }

        var key = emptyToNull(command.idempotencyKey());
        if (key != null) {
            requireUnder(key, IDEMPOTENCY_KEY_MAX, "idempotencyKey");
            var existing = artifactRepository.findByResearchRunIdAndIdempotencyKey(runId, key);
            if (existing.isPresent()) {
                return existing.get(); // idempotent replay — no duplicate, no rework
            }
        }
        requireUnder(command.locator(), LOCATOR_MAX, "locator");
        requireUnder(command.contentHash(), HASH_MAX, "contentHash");

        var activeExisting = artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                runId, expected, ResearchArtifactStatus.ACTIVE);
        var attemptNo = activeExisting.map(a -> a.getAttemptNo() + 1).orElse(1);

        // Supersede the prior ACTIVE record and FLUSH that status change before
        // inserting the replacement, so the single-active-artifact partial unique
        // index is never transiently violated. Hibernate otherwise orders the
        // INSERT before this UPDATE within the flush, leaving two ACTIVE rows.
        var prior = activeExisting.orElse(null);
        if (prior != null) {
            prior.markSuperseded();
            artifactRepository.saveAndFlush(prior);
        }

        var artifact = new ResearchRunArtifact(run, expected, attemptNo);
        artifact.setLocator(emptyToNull(command.locator()));
        artifact.setContentHash(emptyToNull(command.contentHash()));
        artifact.setIdempotencyKey(key);
        artifact.setActor(currentActor());
        var saved = artifactRepository.save(artifact);

        if (prior != null) {
            prior.linkSuperseder(saved.getId());
            artifactRepository.save(prior);
            reopenGuardingGateIfResolved(run);
            if (run.getStatus() == ResearchRunStatus.BLOCKED) {
                run.transitionStatus(ResearchRunStatus.IN_PROGRESS);
            }
        }
        applyCounts(run, command);
        runRepository.save(run);

        log.info(
                "research_run_artifact_recorded: project={} run={} stage={} type={} attempt={} rework={}",
                run.getProject().getIdentifier(),
                runId,
                run.getCurrentStage(),
                expected,
                attemptNo,
                activeExisting.isPresent());
        return saved;
    }

    private void reopenGuardingGateIfResolved(ResearchRun run) {
        ResearchGatePoint.forStageExit(run.getCurrentStage()).ifPresent(point -> gateRepository
                .findByResearchRunIdAndGatePoint(run.getId(), point)
                .filter(g -> g.getBehavior() != ResearchGateBehavior.DISABLED)
                .filter(g -> g.getStatus() == ResearchGateStatus.RESOLVED)
                .ifPresent(g -> {
                    g.reopen();
                    gateRepository.save(g);
                }));
    }

    private void applyCounts(ResearchRun run, RecordArtifactCommand command) {
        if (command.candidateSources() != null) {
            run.setCandidateSources(command.candidateSources());
        }
        if (command.screenedIncluded() != null) {
            run.setScreenedIncluded(command.screenedIncluded());
        }
        if (command.screenedExcluded() != null) {
            run.setScreenedExcluded(command.screenedExcluded());
        }
        if (command.chartedFullText() != null) {
            run.setChartedFullText(command.chartedFullText());
        }
        if (command.accessGaps() != null) {
            run.setAccessGaps(command.accessGaps());
        }
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
    public ResearchRun advanceStage(UUID projectId, UUID runId, AdvanceStageCommand command) {
        var run = requireRun(projectId, runId);
        var targetStage = command == null ? null : command.targetStage();
        if (targetStage == null) {
            throw new DomainValidationException(
                    "targetStage must not be null", INVALID_CODE, Map.of(FIELD, "targetStage"));
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
                    Map.of("current_stage", run.getCurrentStage().name(), "next_stage", next.name()));
        }

        var requiredArtifact = run.getCurrentStage().outputArtifactType();
        var active = artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                runId, requiredArtifact, ResearchArtifactStatus.ACTIVE);
        if (active.isEmpty()) {
            throw new DomainValidationException(
                    "Cannot start " + next + ": required artifact " + requiredArtifact + " for stage "
                            + run.getCurrentStage() + " is missing",
                    "research_run_stage_blocked",
                    Map.of("current_stage", run.getCurrentStage().name(), "missing_artifact", requiredArtifact.name()));
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
            }
            if (!gate.permitsAdvance()) {
                throw new ConflictException(
                        "Gate " + point + " must be resolved before advancing past " + run.getCurrentStage(),
                        "research_run_gate_pending",
                        Map.of(
                                "gate_point",
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

    // ------------------------------------------------------------------
    // Lifecycle: gates
    // ------------------------------------------------------------------

    /** GC-RSCH-R003 — record a durable decision for a run gate. */
    public ResearchRunGate resolveGate(UUID projectId, UUID runId, GateDecisionCommand command) {
        var run = requireRun(projectId, runId);
        if (command == null || command.gatePoint() == null) {
            throw new DomainValidationException("gatePoint must not be null", INVALID_CODE, Map.of(FIELD, "gatePoint"));
        }
        if (command.outcome() == null) {
            throw new DomainValidationException("outcome must not be null", INVALID_CODE, Map.of(FIELD, "outcome"));
        }
        var gate = gateRepository
                .findByResearchRunIdAndGatePoint(runId, command.gatePoint())
                .orElseThrow(
                        () -> new NotFoundException("Gate " + command.gatePoint() + " not found for run " + runId));
        if (gate.getBehavior() == ResearchGateBehavior.DISABLED) {
            throw new DomainValidationException(
                    "Gate " + command.gatePoint() + " is disabled for this run",
                    "research_gate_disabled",
                    Map.of("gate_point", command.gatePoint().name()));
        }
        // A resolved gate (approved, rejected, or auto-accepted) is immutable: the
        // only way to re-decide it is to rework the guarded stage artifact, which
        // supersedes that artifact and reopens the gate (recordArtifact ->
        // reopenGuardingGateIfResolved). Without this guard a caller could REJECT a
        // gate and immediately re-submit APPROVED for the same artifact, advancing
        // past a rejection with no rework — breaking the ADR-063 gate contract.
        if (gate.getStatus() == ResearchGateStatus.RESOLVED) {
            throw new ConflictException(
                    "Gate " + command.gatePoint()
                            + " is already resolved; rework the guarded stage artifact to reopen it",
                    "research_gate_already_resolved",
                    Map.of(
                            "gate_point",
                            command.gatePoint().name(),
                            "outcome",
                            gate.getDecisionOutcome() == null
                                    ? ""
                                    : gate.getDecisionOutcome().name()));
        }
        requireUnder(command.selectedOptionId(), OPTION_ID_MAX, "selectedOptionId");
        requireUnder(command.rationaleSummary(), RATIONALE_MAX, "rationaleSummary");
        gate.resolve(
                command.outcome(),
                emptyToNull(command.selectedOptionId()),
                emptyToNull(command.rationaleSummary()),
                currentActor());
        var savedGate = gateRepository.save(gate);

        if (command.outcome() == ResearchGateDecisionOutcome.REJECTED) {
            if (run.getStatus() == ResearchRunStatus.IN_PROGRESS) {
                run.transitionStatus(ResearchRunStatus.BLOCKED);
                runRepository.save(run);
            }
        } else if (run.getStatus() == ResearchRunStatus.BLOCKED) {
            run.transitionStatus(ResearchRunStatus.IN_PROGRESS);
            runRepository.save(run);
        }
        log.info(
                "research_run_gate_resolved: project={} run={} gate={} outcome={}",
                run.getProject().getIdentifier(),
                runId,
                command.gatePoint(),
                command.outcome());
        return savedGate;
    }

    // ------------------------------------------------------------------
    // Lifecycle: stop / fail / resume / usage
    // ------------------------------------------------------------------

    /** GC-RSCH-F036 — stop an active run; resumable later. */
    public ResearchRun stop(UUID projectId, UUID runId) {
        var run = requireRun(projectId, runId);
        run.transitionStatus(ResearchRunStatus.STOPPED);
        run.setStoppedAt(Instant.now());
        var saved = runRepository.save(run);
        log.info("research_run_stopped: project={} run={}", run.getProject().getIdentifier(), runId);
        return saved;
    }

    /** GC-RSCH-N007 — fail an active run with a bounded failure observation. */
    public ResearchRun fail(UUID projectId, UUID runId, FailRunCommand command) {
        var run = requireRun(projectId, runId);
        requireUnder(command.errorCode(), ERROR_CODE_MAX, "errorCode");
        requireUnder(command.errorClass(), ERROR_CLASS_MAX, "errorClass");
        requireUnder(command.errorSummary(), ERROR_SUMMARY_MAX, "errorSummary");
        run.transitionStatus(ResearchRunStatus.FAILED);
        run.recordError(
                emptyToNull(command.errorCode()),
                emptyToNull(command.errorClass()),
                emptyToNull(command.errorSummary()),
                Instant.now());
        run.setStoppedAt(Instant.now());
        var saved = runRepository.save(run);
        log.info(
                "research_run_failed: project={} run={} error_code={} error_class={}",
                run.getProject().getIdentifier(),
                runId,
                command.errorCode(),
                command.errorClass());
        return saved;
    }

    /**
     * GC-RSCH-F036 / AC3 — resume a stopped or failed run from its last completed
     * stage. Idempotent: no artifacts, gates, or stage state are recreated, so
     * completed work is never duplicated.
     */
    public ResearchRun resume(UUID projectId, UUID runId) {
        var run = requireRun(projectId, runId);
        if (!run.getStatus().isResumable()) {
            throw new DomainValidationException(
                    "Run in status " + run.getStatus() + " is not resumable",
                    "research_run_not_resumable",
                    Map.of("status", run.getStatus().name()));
        }
        run.transitionStatus(ResearchRunStatus.IN_PROGRESS);
        run.setStoppedAt(null);
        var saved = runRepository.save(run);
        log.info(
                "research_run_resumed: project={} run={} stage={}",
                run.getProject().getIdentifier(),
                runId,
                run.getCurrentStage());
        return saved;
    }

    /** GC-RSCH-N011 — record observed usage/cost, separate from budget caps. */
    public ResearchRun recordUsage(UUID projectId, UUID runId, long tokens, long costUsdMicros) {
        var run = requireRun(projectId, runId);
        run.addUsage(tokens, costUsdMicros);
        return runRepository.save(run);
    }

    /** Mark the run COMPLETED once its final-stage artifact is present and ACTIVE. */
    public ResearchRun complete(UUID projectId, UUID runId) {
        var run = requireRun(projectId, runId);
        var stage = run.getCurrentStage();
        if (!stage.isFinal()) {
            throw new DomainValidationException(
                    "Run cannot complete before reaching the final stage",
                    "research_run_not_final_stage",
                    Map.of("current_stage", stage.name()));
        }
        var finalArtifact = artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                runId, stage.outputArtifactType(), ResearchArtifactStatus.ACTIVE);
        if (finalArtifact.isEmpty()) {
            throw new DomainValidationException(
                    "Run cannot complete without an active " + stage.outputArtifactType() + " artifact",
                    "research_run_final_artifact_missing",
                    Map.of("missing_artifact", stage.outputArtifactType().name()));
        }
        run.transitionStatus(ResearchRunStatus.COMPLETED);
        var saved = runRepository.save(run);
        log.info("research_run_completed: project={} run={}", run.getProject().getIdentifier(), runId);
        return saved;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ResearchRun getById(UUID projectId, UUID runId) {
        return requireRun(projectId, runId);
    }

    @Transactional(readOnly = true)
    public ResearchRun getByUid(UUID projectId, String uid) {
        return runRepository
                .findByProjectIdAndUid(projectId, uid)
                .orElseThrow(() -> new NotFoundException("Research run not found: " + uid));
    }

    @Transactional(readOnly = true)
    public List<ResearchRun> listByProject(UUID projectId) {
        return runRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunArtifact> listArtifacts(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return artifactRepository.findByResearchRunIdOrderByCreatedAtAsc(runId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunGate> listGates(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return gateRepository.findByResearchRunIdOrderByGatePointAsc(runId);
    }

    /** GC-RSCH-N011 — assemble the bounded observability snapshot from state. */
    @Transactional(readOnly = true)
    public ResearchRunSnapshot getSnapshot(UUID projectId, UUID runId) {
        var run = requireRun(projectId, runId);
        var artifacts = artifactRepository.findByResearchRunIdOrderByCreatedAtAsc(runId);
        var gates = gateRepository.findByResearchRunIdOrderByGatePointAsc(runId);

        var readiness = new ArrayList<ResearchRunSnapshot.ArtifactReadiness>();
        for (var type : ResearchArtifactType.values()) {
            readiness.add(new ResearchRunSnapshot.ArtifactReadiness(
                    type.producingStage(), type, computeReadiness(artifacts, type)));
        }

        var pendingGates = gates.stream()
                .filter(g -> g.getStatus() == ResearchGateStatus.PENDING)
                .filter(g -> g.getBehavior() == ResearchGateBehavior.REQUIRE_HUMAN)
                .map(g -> new ResearchRunSnapshot.PendingGate(
                        g.getGatePoint(), g.getGatePoint().guardedStageExit()))
                .toList();

        var counts = new ResearchRunSnapshot.SourceCounts(
                run.getCandidateSources(),
                run.getScreenedIncluded(),
                run.getScreenedExcluded(),
                run.getChartedFullText(),
                run.getAccessGaps());
        var cost = new ResearchRunSnapshot.Cost(
                run.getBudgetTokens(),
                run.getBudgetWallClockMinutes(),
                run.getBudgetCostUsdMicros(),
                run.getObservedTokens(),
                run.getObservedCostUsdMicros());
        var lastError = run.getLastErrorCode() == null && run.getLastErrorAt() == null
                ? null
                : new ResearchRunSnapshot.LastError(
                        run.getLastErrorCode(),
                        run.getLastErrorClass(),
                        run.getLastErrorSummary(),
                        run.getLastErrorAt());

        return new ResearchRunSnapshot(
                run.getId(),
                run.getProject().getIdentifier(),
                run.getUid(),
                run.getCurrentStage(),
                run.getStatus(),
                readiness,
                pendingGates,
                counts,
                cost,
                lastError);
    }

    private ResearchArtifactReadiness computeReadiness(List<ResearchRunArtifact> artifacts, ResearchArtifactType type) {
        ResearchArtifactReadiness fallback = ResearchArtifactReadiness.MISSING;
        for (var a : artifacts) {
            if (a.getArtifactType() != type) {
                continue;
            }
            if (a.getStatus() == ResearchArtifactStatus.ACTIVE) {
                return ResearchArtifactReadiness.READY;
            }
            if (a.getStatus() == ResearchArtifactStatus.FAILED) {
                fallback = ResearchArtifactReadiness.FAILED;
            } else if (a.getStatus() == ResearchArtifactStatus.SUPERSEDED
                    && fallback != ResearchArtifactReadiness.FAILED) {
                fallback = ResearchArtifactReadiness.SUPERSEDED;
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResearchRun requireRun(UUID projectId, UUID runId) {
        return runRepository
                .findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> new NotFoundException("Research run not found: " + runId));
    }

    private void requireActive(ResearchRun run) {
        if (run.getStatus() != ResearchRunStatus.IN_PROGRESS && run.getStatus() != ResearchRunStatus.BLOCKED) {
            throw new ConflictException(
                    "Run is not active (status " + run.getStatus() + ")",
                    "research_run_not_active",
                    Map.of("status", run.getStatus().name()));
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

    private void requireUnder(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new DomainValidationException(
                    "Field " + field + " exceeds max length", INVALID_CODE, Map.of(FIELD, field, "max", max));
        }
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The authenticated actor for durable lifecycle provenance. Always the
     * server-side {@link ActorHolder} context populated by {@code ActorFilter};
     * clients never supply the audit actor on the write boundary (ADR-026), so
     * artifact/gate/owner provenance cannot be forged via the request payload.
     */
    private String currentActor() {
        return emptyToNull(ActorHolder.get());
    }
}
