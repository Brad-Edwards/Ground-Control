package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContractEntryKind;
import com.keplerops.groundcontrol.domain.research.model.DisclosureEntryFamily;
import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContract;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntry;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntrySourceLink;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractRejectedAlternative;
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.ProtocolCoverageDisposition;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlan;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanCoverage;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanSection;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSectionKind;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSourceRole;
import com.keplerops.groundcontrol.domain.research.model.RationaleEntryKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosure;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosureEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGate;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunRationaleEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentTarget;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractEntrySourceLinkRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRejectedAlternativeRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanCoverageRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanSectionRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchIntakeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateDecisionLogRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySelectionRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySourceRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRationaleEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunReviewCommentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-RSCH-R001/R003/F003/F006/F036/N007/N011 — application service for the {@link
 * ResearchRun} aggregate (ADR-064 / ADR-065).
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
    private static final int SUBJECT_KEY_MAX = 200;
    private static final int SUMMARY_MAX = 2000;
    private static final int BODY_MAX = 2000;
    private static final int CONFIDENCE_MAX = 500;
    private static final int SECTION_KEY_MAX = 200;
    private static final int MODEL_LABEL_MAX = 200;
    private static final int QUESTION_KEY_MAX = 200;
    private static final int ACTION_ID_MAX = 200;
    private static final int RECOMMENDATION_SUMMARY_MAX = 1000;
    private static final int METHOD_KEY_MAX = 200;
    private static final int SOURCE_REF_MAX = 500;
    private static final int SOURCE_LABEL_MAX = 500;
    private static final int ENTRY_KEY_MAX = 200;
    private static final int STATEMENT_MAX = 2000;
    private static final int PROFILE_VERSION_MAX = 100;
    private static final String CONTRACT_SCHEMA_VERSION = "1";
    private static final String CONTRACT_ENTRY_KEY_FIELD = "entryKey";
    private static final String LOCATOR_FIELD = "locator";
    private static final String METHOD_KEY_FIELD = "methodKey";
    private static final String REFERENCES_ENTRY_KEY_FIELD = "references_entry_key";
    private static final String SOURCE_ID_FIELD = "source_id";
    private static final int PROTOCOL_SCHEMA_VERSION_MAX = 40;
    private static final int ANSWER_SUMMARY_MAX = 2000;
    private static final int PROTOCOL_RATIONALE_MAX = 2000;
    private static final int DECISION_REFERENCE_MAX = 200;
    private static final String CONTRACT_ENTRY_KEY_JSON_FIELD = "contractEntryKey";
    private static final String DISPOSITION_FIELD = "disposition";
    private static final String SECTION_KEY_JSON_FIELD = "sectionKey";
    private static final String SECTION_KIND_FIELD = "sectionKind";

    private static final String AUTONOMOUS_DEFAULT_BASIS = "AUTONOMOUS_DEFAULT";

    private static final String INVALID_CODE = "research_run_invalid";
    private static final String FIELD = "field";
    private static final String NO_ACTIVE_METHODOLOGY_SELECTION = "No active methodology selection for run ";
    private static final String CURRENT_STAGE = "current_stage";
    private static final String GATE_POINT = "gate_point";
    private static final String TARGET_STAGE = "targetStage";
    private static final String RATIONALE_SUMMARY = "rationaleSummary";
    private static final String TARGET_ARTIFACT_ID = "targetArtifactId";
    private static final String TARGET_DECISION_LOG_ID = "targetDecisionLogId";

    private final ResearchRunRepository runRepository;
    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunGateRepository gateRepository;
    private final ResearchRunGateDecisionLogRepository decisionLogRepository;
    private final ResearchRunReviewCommentRepository reviewCommentRepository;
    private final ResearchRunRationaleEntryRepository rationaleRepository;
    private final ResearchRunDisclosureRepository disclosureRepository;
    private final ResearchRunDisclosureEntryRepository disclosureEntryRepository;
    private final ResearchIntakeRepository intakeRepository;
    private final ProjectService projectService;
    private final ResearchRunMethodologySelectionRepository methodologySelectionRepository;
    private final ResearchRunMethodologySourceRepository methodologySourceRepository;
    private final MethodologyCatalog methodologyCatalog;
    private final MethodologyRequirementsContractRepository contractRepository;
    private final MethodologyRequirementsContractEntryRepository contractEntryRepository;
    private final MethodologyRequirementsContractEntrySourceLinkRepository contractEntrySourceLinkRepository;
    private final MethodologyRequirementsContractRejectedAlternativeRepository contractRejectedAlternativeRepository;
    private final ProtocolPlanRepository protocolPlanRepository;
    private final ProtocolPlanCoverageRepository protocolPlanCoverageRepository;
    private final ProtocolPlanSectionRepository protocolPlanSectionRepository;

    public ResearchRunService(
            ResearchRunRepository runRepository,
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunGateRepository gateRepository,
            ResearchRunGateDecisionLogRepository decisionLogRepository,
            ResearchRunReviewCommentRepository reviewCommentRepository,
            ResearchRunRationaleEntryRepository rationaleRepository,
            ResearchRunDisclosureRepository disclosureRepository,
            ResearchRunDisclosureEntryRepository disclosureEntryRepository,
            ResearchIntakeRepository intakeRepository,
            ProjectService projectService,
            ResearchRunMethodologySelectionRepository methodologySelectionRepository,
            ResearchRunMethodologySourceRepository methodologySourceRepository,
            MethodologyCatalog methodologyCatalog,
            MethodologyRequirementsContractRepository contractRepository,
            MethodologyRequirementsContractEntryRepository contractEntryRepository,
            MethodologyRequirementsContractEntrySourceLinkRepository contractEntrySourceLinkRepository,
            MethodologyRequirementsContractRejectedAlternativeRepository contractRejectedAlternativeRepository,
            ProtocolPlanRepository protocolPlanRepository,
            ProtocolPlanCoverageRepository protocolPlanCoverageRepository,
            ProtocolPlanSectionRepository protocolPlanSectionRepository) {
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.gateRepository = gateRepository;
        this.decisionLogRepository = decisionLogRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.rationaleRepository = rationaleRepository;
        this.disclosureRepository = disclosureRepository;
        this.disclosureEntryRepository = disclosureEntryRepository;
        this.intakeRepository = intakeRepository;
        this.projectService = projectService;
        this.methodologySelectionRepository = methodologySelectionRepository;
        this.methodologySourceRepository = methodologySourceRepository;
        this.methodologyCatalog = methodologyCatalog;
        this.contractRepository = contractRepository;
        this.contractEntryRepository = contractEntryRepository;
        this.contractEntrySourceLinkRepository = contractEntrySourceLinkRepository;
        this.contractRejectedAlternativeRepository = contractRejectedAlternativeRepository;
        this.protocolPlanRepository = protocolPlanRepository;
        this.protocolPlanCoverageRepository = protocolPlanCoverageRepository;
        this.protocolPlanSectionRepository = protocolPlanSectionRepository;
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
                    Map.of(CURRENT_STAGE, run.getCurrentStage().name(), "expected", expected.name()));
        }

        // GC-RSCH-F006 — methodology source coverage gate: all required sources must
        // be READ before the METHODOLOGY_REQUIREMENTS artifact can be recorded.
        if (command.artifactType() == ResearchArtifactType.METHODOLOGY_REQUIREMENTS) {
            requireMethodologySourceCoverageComplete(runId);
        }

        var key = emptyToNull(command.idempotencyKey());
        if (key != null) {
            requireUnder(key, IDEMPOTENCY_KEY_MAX, "idempotencyKey");
            var existing = artifactRepository.findByResearchRunIdAndIdempotencyKey(runId, key);
            if (existing.isPresent()) {
                return existing.get(); // idempotent replay — no duplicate, no rework
            }
        }
        requireUnder(command.locator(), LOCATOR_MAX, LOCATOR_FIELD);
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
            if (expected == ResearchArtifactType.MANUSCRIPT) {
                staleCurrentDisclosure(run);
            }
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

        // GC-RSCH-F008 / ADR-081 §2 — the SOURCE_SEARCH durable gate: an active
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
                    Map.of(GATE_POINT, command.gatePoint().name()));
        }
        // A resolved gate (approved, rejected, or auto-accepted) is immutable: the
        // only way to re-decide it is to rework the guarded stage artifact, which
        // supersedes that artifact and reopens the gate (recordArtifact ->
        // reopenGuardingGateIfResolved). Without this guard a caller could REJECT a
        // gate and immediately re-submit APPROVED for the same artifact, advancing
        // past a rejection with no rework — breaking the ADR-064 gate contract.
        if (gate.getStatus() == ResearchGateStatus.RESOLVED) {
            throw new ConflictException(
                    "Gate " + command.gatePoint()
                            + " is already resolved; rework the guarded stage artifact to reopen it",
                    "research_gate_already_resolved",
                    Map.of(
                            GATE_POINT,
                            command.gatePoint().name(),
                            "outcome",
                            gate.getDecisionOutcome() == null
                                    ? ""
                                    : gate.getDecisionOutcome().name()));
        }
        requireUnder(command.selectedOptionId(), OPTION_ID_MAX, "selectedOptionId");
        requireUnder(command.rationaleSummary(), RATIONALE_MAX, RATIONALE_SUMMARY);
        requireUnder(command.recommendationOptionId(), OPTION_ID_MAX, "recommendationOptionId");
        requireUnder(command.recommendationSummary(), RECOMMENDATION_SUMMARY_MAX, "recommendationSummary");
        requireUnder(command.questionKey(), QUESTION_KEY_MAX, "questionKey");
        requireUnder(command.sourceActionId(), ACTION_ID_MAX, "sourceActionId");
        var actor = currentActor();
        gate.resolve(
                command.outcome(),
                emptyToNull(command.selectedOptionId()),
                emptyToNull(command.rationaleSummary()),
                actor);
        var savedGate = gateRepository.save(gate);
        appendDecisionLog(run, command, actor);

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
    // Decision surfaces: review comments / rationale / disclosure
    // ------------------------------------------------------------------

    /** GC-RSCH-F034 / ADR-067 — attach a bounded review comment to a run surface. */
    public ResearchRunReviewComment addReviewComment(UUID projectId, UUID runId, AddReviewCommentCommand command) {
        var run = requireRun(projectId, runId);
        if (command == null || command.targetType() == null) {
            throw new DomainValidationException(
                    "targetType must not be null", INVALID_CODE, Map.of(FIELD, "targetType"));
        }
        if (command.provenance() == null) {
            throw new DomainValidationException(
                    "provenance must not be null", INVALID_CODE, Map.of(FIELD, "provenance"));
        }
        if (command.body() == null || command.body().isBlank()) {
            throw new DomainValidationException("body must not be blank", INVALID_CODE, Map.of(FIELD, "body"));
        }
        requireUnder(command.body(), BODY_MAX, "body");
        validateReviewTargetConsistency(command);
        requireSameRunReference(
                command.targetArtifactId(), runId, artifactRepository::existsByIdAndResearchRunId, TARGET_ARTIFACT_ID);
        requireSameRunReference(
                command.targetDecisionLogId(),
                runId,
                decisionLogRepository::existsByIdAndResearchRunId,
                TARGET_DECISION_LOG_ID);

        var comment = new ResearchRunReviewComment(
                run, command.targetType(), command.body().trim(), command.provenance(), currentActor());
        comment.setTargetGatePoint(command.targetGatePoint());
        comment.setTargetStage(command.targetStage());
        comment.setTargetArtifactId(command.targetArtifactId());
        comment.setTargetDecisionLogId(command.targetDecisionLogId());
        var saved = reviewCommentRepository.save(comment);
        log.info(
                "research_run_review_comment_added: project={} run={} target={} provenance={}",
                run.getProject().getIdentifier(),
                runId,
                command.targetType(),
                command.provenance());
        return saved;
    }

    /** GC-RSCH-F034 / ADR-067 — resolve an open review comment; never touches gate/stage state. */
    public ResearchRunReviewComment resolveReviewComment(
            UUID projectId, UUID runId, UUID commentId, ResolveReviewCommentCommand command) {
        requireRun(projectId, runId);
        var comment = reviewCommentRepository
                .findById(commentId)
                .filter(c -> c.getResearchRun().getId().equals(runId))
                .orElseThrow(() -> new NotFoundException("Review comment not found: " + commentId));
        var summary = command == null ? null : command.resolutionSummary();
        requireUnder(summary, RATIONALE_MAX, "resolutionSummary");
        comment.resolve(emptyToNull(summary), currentActor());
        var saved = reviewCommentRepository.save(comment);
        log.info("research_run_review_comment_resolved: run={} comment={}", runId, commentId);
        return saved;
    }

    /** GC-RSCH-N012 / ADR-068 — append an immutable rationale-ledger entry. */
    public ResearchRunRationaleEntry addRationaleEntry(UUID projectId, UUID runId, AddRationaleEntryCommand command) {
        var run = requireRun(projectId, runId);
        if (command == null) {
            throw new DomainValidationException("command must not be null", INVALID_CODE, Map.of());
        }
        if (command.stage() == null) {
            throw new DomainValidationException("stage must not be null", INVALID_CODE, Map.of(FIELD, "stage"));
        }
        if (command.kind() == null) {
            throw new DomainValidationException("kind must not be null", INVALID_CODE, Map.of(FIELD, "kind"));
        }
        if (command.evidenceBasis() == null) {
            throw new DomainValidationException(
                    "evidenceBasis must not be null", INVALID_CODE, Map.of(FIELD, "evidenceBasis"));
        }
        if (command.provenance() == null) {
            throw new DomainValidationException(
                    "provenance must not be null", INVALID_CODE, Map.of(FIELD, "provenance"));
        }
        if (command.subjectKey() == null || command.subjectKey().isBlank()) {
            throw new DomainValidationException(
                    "subjectKey must not be blank", INVALID_CODE, Map.of(FIELD, "subjectKey"));
        }
        if (command.rationaleSummary() == null || command.rationaleSummary().isBlank()) {
            throw new DomainValidationException(
                    "rationaleSummary must not be blank", INVALID_CODE, Map.of(FIELD, RATIONALE_SUMMARY));
        }
        requireUnder(command.subjectKey(), SUBJECT_KEY_MAX, "subjectKey");
        requireUnder(command.rationaleSummary(), SUMMARY_MAX, RATIONALE_SUMMARY);
        requireUnder(command.evidenceLocator(), LOCATOR_MAX, "evidenceLocator");
        requireUnder(command.confidenceSummary(), CONFIDENCE_MAX, "confidenceSummary");
        validateRationaleLifecycleConsistency(runId, command);

        var entry = new ResearchRunRationaleEntry(
                run,
                command.stage(),
                command.kind(),
                command.evidenceBasis(),
                command.provenance(),
                command.subjectKey().trim(),
                command.rationaleSummary().trim(),
                currentActor(),
                Instant.now());
        entry.setArtifactType(command.artifactType());
        entry.setArtifactId(command.artifactId());
        entry.setAttemptNo(command.attemptNo());
        entry.setGatePoint(command.gatePoint());
        entry.setEvidenceLocator(emptyToNull(command.evidenceLocator()));
        entry.setConfidenceSummary(emptyToNull(command.confidenceSummary()));
        var saved = rationaleRepository.save(entry);
        log.info(
                "research_run_rationale_added: project={} run={} stage={} kind={} provenance={}",
                run.getProject().getIdentifier(),
                runId,
                command.stage(),
                command.kind(),
                command.provenance());
        return saved;
    }

    /**
     * Validate a rationale entry's lifecycle references before persisting (ADR-067
     * assigns stage/artifact/gate consistency to the service). A supplied artifact
     * reference is resolved within the run and its actual type/attempt must match
     * the declared {@code artifactType}/{@code attemptNo}; a supplied gate point
     * must guard the entry's stage. This stops a caller from recording, say, a
     * CHARTING rationale against a MANUSCRIPT artifact or a gate that does not
     * guard the stage, which later reads would treat as authoritative.
     */
    private void validateRationaleLifecycleConsistency(UUID runId, AddRationaleEntryCommand command) {
        if (command.artifactId() != null) {
            var artifact = artifactRepository
                    .findByIdAndResearchRunId(command.artifactId(), runId)
                    .orElseThrow(() -> new NotFoundException(
                            "artifactId " + command.artifactId() + " was not found for run " + runId));
            if (command.artifactType() != null && artifact.getArtifactType() != command.artifactType()) {
                throw new DomainValidationException(
                        "artifactType does not match the referenced artifact",
                        "research_rationale_reference_invalid",
                        Map.of(FIELD, "artifactType"));
            }
            if (command.attemptNo() != null && !command.attemptNo().equals(artifact.getAttemptNo())) {
                throw new DomainValidationException(
                        "attemptNo does not match the referenced artifact",
                        "research_rationale_reference_invalid",
                        Map.of(FIELD, "attemptNo"));
            }
        }
        if (command.gatePoint() != null && command.gatePoint().guardedStageExit() != command.stage()) {
            throw new DomainValidationException(
                    "gatePoint does not guard the rationale stage",
                    "research_rationale_reference_invalid",
                    Map.of(FIELD, "gatePoint", "stage", command.stage().name()));
        }
    }

    /** GC-RSCH-N013 / ADR-068 §4 — create the disclosure tied to the active manuscript. */
    public ResearchRunDisclosure createDisclosure(UUID projectId, UUID runId, CreateDisclosureCommand command) {
        var run = requireRun(projectId, runId);
        if (command == null) {
            throw new DomainValidationException("command must not be null", INVALID_CODE, Map.of());
        }
        var manuscript = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new DomainValidationException(
                        "Cannot create a disclosure without an active MANUSCRIPT artifact",
                        "research_run_disclosure_no_manuscript",
                        Map.of()));
        // The command pins the manuscript the disclosure covers; reject a stale or
        // mismatched pin so a disclosure can never be attached to a superseded
        // manuscript attempt (ADR-068 §4).
        if (command.finalArtifactId() != null && !command.finalArtifactId().equals(manuscript.getId())) {
            throw new DomainValidationException(
                    "finalArtifactId does not match the active MANUSCRIPT artifact",
                    "research_run_disclosure_artifact_mismatch",
                    Map.of(FIELD, "finalArtifactId"));
        }
        if (command.finalAttemptNo() != null && !command.finalAttemptNo().equals(manuscript.getAttemptNo())) {
            throw new DomainValidationException(
                    "finalAttemptNo does not match the active MANUSCRIPT artifact",
                    "research_run_disclosure_artifact_mismatch",
                    Map.of(FIELD, "finalAttemptNo"));
        }
        // Single-current invariant (backed by the partial unique index in V158): a
        // double-submit for the same active manuscript returns the existing record
        // idempotently; a stray CURRENT row for a different manuscript is a conflict.
        var existingCurrent = disclosureRepository.findFirstByResearchRunIdAndStatus(runId, DisclosureStatus.CURRENT);
        if (existingCurrent.isPresent()) {
            var current = existingCurrent.get();
            if (current.getFinalArtifactId().equals(manuscript.getId())) {
                return current;
            }
            throw new ConflictException(
                    "A current disclosure already exists for this run",
                    "research_run_disclosure_exists",
                    Map.of("disclosure_id", current.getId().toString()));
        }
        var disclosure = new ResearchRunDisclosure(
                run,
                manuscript.getId(),
                manuscript.getAttemptNo(),
                command.aiPartsDeclaredNone(),
                command.uncertaintyDeclaredNone(),
                command.humanApprovalsDeclaredNone(),
                currentActor());
        var saved = disclosureRepository.save(disclosure);
        log.info(
                "research_run_disclosure_created: project={} run={} disclosure={} manuscript_attempt={}",
                run.getProject().getIdentifier(),
                runId,
                saved.getId(),
                manuscript.getAttemptNo());
        return saved;
    }

    /** GC-RSCH-N013 / ADR-068 §4 — add one entry to a current disclosure. */
    public ResearchRunDisclosureEntry addDisclosureEntry(
            UUID projectId, UUID runId, UUID disclosureId, AddDisclosureEntryCommand command) {
        requireRun(projectId, runId);
        if (command == null || command.family() == null) {
            throw new DomainValidationException("family must not be null", INVALID_CODE, Map.of(FIELD, "family"));
        }
        var disclosure = disclosureRepository
                .findById(disclosureId)
                .filter(d -> d.getResearchRun().getId().equals(runId))
                .orElseThrow(() -> new NotFoundException("Disclosure not found: " + disclosureId));
        if (disclosure.getStatus() == DisclosureStatus.STALE) {
            throw new ConflictException(
                    "Cannot add entries to a stale disclosure",
                    "research_run_disclosure_stale",
                    Map.of("disclosure_id", disclosureId.toString()));
        }
        boolean isUncertainty = command.family() == DisclosureEntryFamily.UNRESOLVED_UNCERTAINTY;
        if (isUncertainty && command.uncertaintyCategory() == null) {
            throw new DomainValidationException(
                    "uncertaintyCategory is required for an UNRESOLVED_UNCERTAINTY entry",
                    INVALID_CODE,
                    Map.of(FIELD, "uncertaintyCategory"));
        }
        if (!isUncertainty && command.uncertaintyCategory() != null) {
            throw new DomainValidationException(
                    "uncertaintyCategory is only valid for an UNRESOLVED_UNCERTAINTY entry",
                    INVALID_CODE,
                    Map.of(FIELD, "uncertaintyCategory"));
        }
        if (command.summary() == null || command.summary().isBlank()) {
            throw new DomainValidationException("summary must not be blank", INVALID_CODE, Map.of(FIELD, "summary"));
        }
        requireUnder(command.summary(), SUMMARY_MAX, "summary");
        requireUnder(command.sectionKey(), SECTION_KEY_MAX, "sectionKey");
        requireUnder(command.locator(), LOCATOR_MAX, LOCATOR_FIELD);
        requireUnder(command.modelLabel(), MODEL_LABEL_MAX, "modelLabel");
        requireSameRunReference(
                command.rationaleEntryId(), runId, rationaleRepository::existsByIdAndResearchRunId, "rationaleEntryId");
        requireSameRunReference(
                command.decisionLogId(), runId, decisionLogRepository::existsByIdAndResearchRunId, "decisionLogId");
        requireSameRunReference(
                command.reviewCommentId(),
                runId,
                reviewCommentRepository::existsByIdAndResearchRunId,
                "reviewCommentId");

        var entry = new ResearchRunDisclosureEntry(
                disclosure, command.family(), command.summary().trim(), currentActor());
        entry.setUncertaintyCategory(command.uncertaintyCategory());
        entry.setSectionKey(emptyToNull(command.sectionKey()));
        entry.setLocator(emptyToNull(command.locator()));
        entry.setModelLabel(emptyToNull(command.modelLabel()));
        entry.setRationaleEntryId(command.rationaleEntryId());
        entry.setDecisionLogId(command.decisionLogId());
        entry.setReviewCommentId(command.reviewCommentId());
        var saved = disclosureEntryRepository.save(entry);
        log.info(
                "research_run_disclosure_entry_added: run={} disclosure={} family={}",
                runId,
                disclosureId,
                command.family());
        return saved;
    }

    private void validateReviewTargetConsistency(AddReviewCommentCommand command) {
        switch (command.targetType()) {
            case GATE_POINT -> requirePresent(command.targetGatePoint(), "targetGatePoint");
            case STAGE -> requirePresent(command.targetStage(), TARGET_STAGE);
            case ARTIFACT -> requirePresent(command.targetArtifactId(), TARGET_ARTIFACT_ID);
            case DECISION_LOG -> requirePresent(command.targetDecisionLogId(), TARGET_DECISION_LOG_ID);
            case RUN -> {
                // RUN targets carry no discriminator.
            }
            default -> throw new IllegalStateException("Unhandled review-comment target: " + command.targetType());
        }
        if (command.targetType() != ReviewCommentTarget.GATE_POINT && command.targetGatePoint() != null) {
            throw inconsistentTarget("targetGatePoint", command.targetType());
        }
        if (command.targetType() != ReviewCommentTarget.STAGE && command.targetStage() != null) {
            throw inconsistentTarget(TARGET_STAGE, command.targetType());
        }
        if (command.targetType() != ReviewCommentTarget.ARTIFACT && command.targetArtifactId() != null) {
            throw inconsistentTarget(TARGET_ARTIFACT_ID, command.targetType());
        }
        if (command.targetType() != ReviewCommentTarget.DECISION_LOG && command.targetDecisionLogId() != null) {
            throw inconsistentTarget(TARGET_DECISION_LOG_ID, command.targetType());
        }
    }

    private void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainValidationException(
                    field + " is required for this target type",
                    "research_review_comment_target_invalid",
                    Map.of(FIELD, field));
        }
    }

    /**
     * Reject a request-supplied cross-record reference UUID that does not resolve
     * to a row owned by the same run. Without this guard a comment, rationale, or
     * disclosure entry on one run could point at another run's artifact, decision
     * log, rationale, or comment, and later reads would treat it as authoritative
     * metadata for the owning run (ADR-066/067/068 run-scoped product graph). A
     * null reference is optional and skipped; the cross-run miss is concealed as
     * {@link NotFoundException} like every other run-scoped lookup (GC-RS-009).
     */
    private void requireSameRunReference(
            UUID referenceId, UUID runId, java.util.function.BiPredicate<UUID, UUID> existsInRun, String field) {
        if (referenceId != null && !existsInRun.test(referenceId, runId)) {
            throw new NotFoundException(field + " " + referenceId + " was not found for run " + runId);
        }
    }

    private DomainValidationException inconsistentTarget(String field, ReviewCommentTarget target) {
        return new DomainValidationException(
                field + " must not be set for target type " + target,
                "research_review_comment_target_invalid",
                Map.of(FIELD, field, "target_type", target.name()));
    }

    /**
     * The attempt number of the active artifact produced by the stage this gate
     * guards, or null when none is recorded yet. Tying each decision-log row to a
     * concrete artifact attempt lets two decisions on the same run/gate after a
     * rework be reconciled to the superseded versus current attempt (ADR-066),
     * instead of relying on chronology alone.
     */
    private Integer activeAttemptForGate(ResearchRun run, ResearchGatePoint gatePoint) {
        var guardedStage = gatePoint.guardedStageExit();
        return artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        run.getId(), guardedStage.outputArtifactType(), ResearchArtifactStatus.ACTIVE)
                .map(ResearchRunArtifact::getAttemptNo)
                .orElse(null);
    }

    private void appendDecisionLog(ResearchRun run, GateDecisionCommand command, String actor) {
        var gatePoint = command.gatePoint();
        var entry = new ResearchRunGateDecisionLog(
                run, gatePoint, gatePoint.guardedStageExit(), command.outcome(), actor, Instant.now());
        entry.setArtifactAttemptNo(activeAttemptForGate(run, gatePoint));
        entry.setQuestionKey(emptyToNull(command.questionKey()));
        entry.setRecommendationOptionId(emptyToNull(command.recommendationOptionId()));
        entry.setRecommendationSummary(emptyToNull(command.recommendationSummary()));
        entry.setRecommendationProvenance(command.recommendationProvenance());
        entry.setSelectedOptionId(emptyToNull(command.selectedOptionId()));
        entry.setRationaleSummary(emptyToNull(command.rationaleSummary()));
        entry.setSourceActionId(emptyToNull(command.sourceActionId()));
        decisionLogRepository.save(entry);
        log.info("research_run_decision_logged: run={} gate={} outcome={}", run.getId(), gatePoint, command.outcome());
    }

    private void appendAutonomousDefaultDecisionLog(ResearchRun run, ResearchGatePoint gatePoint) {
        var entry = new ResearchRunGateDecisionLog(
                run,
                gatePoint,
                gatePoint.guardedStageExit(),
                ResearchGateDecisionOutcome.AUTO_ACCEPTED,
                run.getOwnerActor(),
                Instant.now());
        entry.setArtifactAttemptNo(activeAttemptForGate(run, gatePoint));
        entry.setPolicyBasis(AUTONOMOUS_DEFAULT_BASIS);
        decisionLogRepository.save(entry);
        log.info(
                "research_run_decision_logged: run={} gate={} outcome={} basis={}",
                run.getId(),
                gatePoint,
                ResearchGateDecisionOutcome.AUTO_ACCEPTED,
                AUTONOMOUS_DEFAULT_BASIS);
    }

    private void staleCurrentDisclosure(ResearchRun run) {
        disclosureRepository
                .findFirstByResearchRunIdAndStatus(run.getId(), DisclosureStatus.CURRENT)
                .ifPresent(disclosure -> {
                    disclosure.markStale();
                    disclosureRepository.save(disclosure);
                    log.info("research_run_disclosure_staled: run={} disclosure={}", run.getId(), disclosure.getId());
                });
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

    /**
     * Mark the run COMPLETED once its final-stage artifact is present and ACTIVE
     * and the manuscript's disclosure is complete (ADR-068 §4). A CURRENT
     * disclosure tied to the active manuscript must exist, and both disclosure
     * families (AI-generated parts, unresolved uncertainty) must be covered —
     * either by at least one entry of that family or by the matching
     * declared-none flag. AUTO_ACCEPTED gate decisions never count as human
     * approval, so disclosure is required regardless of how gates were resolved.
     */
    public ResearchRun complete(UUID projectId, UUID runId) {
        var run = requireRun(projectId, runId);
        var stage = run.getCurrentStage();
        if (!stage.isFinal()) {
            throw new DomainValidationException(
                    "Run cannot complete before reaching the final stage",
                    "research_run_not_final_stage",
                    Map.of(CURRENT_STAGE, stage.name()));
        }
        var finalArtifact = artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                runId, stage.outputArtifactType(), ResearchArtifactStatus.ACTIVE);
        if (finalArtifact.isEmpty()) {
            throw new DomainValidationException(
                    "Run cannot complete without an active " + stage.outputArtifactType() + " artifact",
                    "research_run_final_artifact_missing",
                    Map.of("missing_artifact", stage.outputArtifactType().name()));
        }
        requireCompleteDisclosure(runId, finalArtifact.get().getId());
        run.transitionStatus(ResearchRunStatus.COMPLETED);
        var saved = runRepository.save(run);
        log.info("research_run_completed: project={} run={}", run.getProject().getIdentifier(), runId);
        return saved;
    }

    private void requireCompleteDisclosure(UUID runId, UUID activeManuscriptId) {
        var current = disclosureRepository.findFirstByResearchRunIdAndStatus(runId, DisclosureStatus.CURRENT);
        var staleExists = disclosureRepository
                .findFirstByResearchRunIdAndStatus(runId, DisclosureStatus.STALE)
                .isPresent();
        if (current.isEmpty()) {
            if (staleExists) {
                throw new DomainValidationException(
                        "Run cannot complete: the manuscript disclosure is stale and must be re-created",
                        "research_run_disclosure_stale",
                        Map.of());
            }
            throw new DomainValidationException(
                    "Run cannot complete without a current manuscript disclosure",
                    "research_run_disclosure_missing",
                    Map.of());
        }
        var disclosure = current.get();
        if (!activeManuscriptId.equals(disclosure.getFinalArtifactId())) {
            throw new DomainValidationException(
                    "Run cannot complete: the current disclosure does not cover the active manuscript",
                    "research_run_disclosure_stale",
                    Map.of());
        }
        var entries = disclosureEntryRepository.findByDisclosureId(disclosure.getId());
        var aiCovered = disclosure.isAiPartsDeclaredNone()
                || entries.stream().anyMatch(e -> e.getFamily() == DisclosureEntryFamily.AI_GENERATED_PART);
        var uncertaintyCovered = disclosure.isUncertaintyDeclaredNone()
                || entries.stream().anyMatch(e -> e.getFamily() == DisclosureEntryFamily.UNRESOLVED_UNCERTAINTY);
        // ADR-068 §4 requires all three accountability families. Human approvals are
        // derived from the gate decision log (a human APPROVED outcome); AUTO_ACCEPTED
        // is autonomous and never counts, so a fully autonomous run must explicitly
        // declare no human approvals rather than silently omitting the family.
        var humanApprovalsCovered = disclosure.isHumanApprovalsDeclaredNone()
                || decisionLogRepository.existsByResearchRunIdAndDecisionOutcome(
                        runId, ResearchGateDecisionOutcome.APPROVED);
        if (!aiCovered || !uncertaintyCovered || !humanApprovalsCovered) {
            throw new DomainValidationException(
                    "Run cannot complete: the manuscript disclosure is incomplete",
                    "research_run_disclosure_incomplete",
                    Map.of(
                            "ai_parts_covered", String.valueOf(aiCovered),
                            "uncertainty_covered", String.valueOf(uncertaintyCovered),
                            "human_approvals_covered", String.valueOf(humanApprovalsCovered)));
        }
    }

    // ------------------------------------------------------------------
    // Methodology selection + source coverage (GC-RSCH-F006)
    // ------------------------------------------------------------------

    /**
     * GC-RSCH-F006 / ADR-078 — select (or re-select) the active methodology for a
     * run. The selected {@code methodKey} is resolved against the backend-owned
     * methodology catalog; the label, profile/catalog version, and required
     * primary-source set are all DERIVED from the catalog profile (never supplied
     * by the caller). Each required source is snapshotted as an immutable
     * {@code required=true} row in {@code ATTEMPTED} state.
     *
     * <p>Idempotent when the same method is re-selected and the snapshotted
     * required sources still match the catalog profile (no catalog drift): the
     * existing selection is returned unchanged, preserving any recorded source
     * progress. Selecting a different method (or a profile whose required-source
     * set has since changed) supersedes the prior active selection and re-snapshots.
     */
    public ResearchRunMethodologySelection selectMethodology(UUID projectId, UUID runId, SelectMethodologyCommand cmd) {
        var run = requireRun(projectId, runId);
        requireActive(run);
        if (cmd == null || cmd.methodKey() == null || cmd.methodKey().isBlank()) {
            throw new DomainValidationException(
                    "methodKey must not be blank", INVALID_CODE, Map.of(FIELD, METHOD_KEY_FIELD));
        }
        var methodKey = cmd.methodKey().trim();
        requireUnder(methodKey, METHOD_KEY_MAX, METHOD_KEY_FIELD);
        var profile = methodologyCatalog.requireProfile(methodKey);

        var existing = methodologySelectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(runId);
        if (existing.isPresent()) {
            var sel = existing.get();
            // Idempotent re-select: same method+versions and the snapshotted
            // required source refs still match the catalog profile → return existing
            // unchanged (does not re-open or discard recorded source progress).
            var sameTuple = Objects.equals(sel.getMethodKey(), profile.methodKey())
                    && Objects.equals(sel.getProfileVersion(), profile.profileVersion())
                    && Objects.equals(sel.getCatalogVersion(), profile.catalogVersion());
            if (sameTuple) {
                var existingSources = methodologySourceRepository.findBySelectionId(sel.getId());
                if (requiredRefsMatchProfile(existingSources, profile)) {
                    return sel;
                }
            }
            // Superseding the active selection re-snapshots a fresh (unread) required
            // set, which would leave an already-accepted METHODOLOGY_REQUIREMENTS
            // artifact's coverage unsatisfied by the new selection. Methodology is
            // therefore locked once its requirements artifact is recorded — reselection
            // is rejected rather than silently invalidating accepted downstream state.
            if (artifactRepository
                    .findByResearchRunIdAndArtifactTypeAndStatus(
                            runId, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)
                    .isPresent()) {
                throw new ConflictException(
                        "Methodology cannot be changed after the METHODOLOGY_REQUIREMENTS artifact has been recorded",
                        "research_run_methodology_locked_after_requirements",
                        Map.of("method_key", sel.getMethodKey()));
            }
            // Supersede prior selection before creating the new one.
            sel.supersede();
            methodologySelectionRepository.save(sel);
        }

        var actor = currentActor();
        var selection = new ResearchRunMethodologySelection(run, profile.methodKey(), actor);
        selection.setMethodLabel(profile.label());
        selection.setProfileVersion(profile.profileVersion());
        selection.setCatalogVersion(profile.catalogVersion());
        var saved = methodologySelectionRepository.save(selection);

        // Snapshot the catalog profile's required sources as immutable required=true
        // rows on the new selection. The required-source set is derived from the
        // selected method+version, not from the request.
        for (var source : profile.requiredSources()) {
            var row = new ResearchRunMethodologySource(saved, source.ref(), true, actor);
            row.setSourceLabel(source.title());
            methodologySourceRepository.save(row);
        }

        log.info(
                "research_run_methodology_selected: project={} run={} selection={} methodKey={} version={} requiredRefs={}",
                run.getProject().getIdentifier(),
                runId,
                saved.getId(),
                profile.methodKey(),
                profile.profileVersion(),
                profile.requiredSources().size());
        return saved;
    }

    /**
     * True when the required source refs already snapshotted on an active selection
     * exactly match the catalog profile's required-source set. Used to decide
     * whether re-selecting the same method is idempotent or must re-snapshot after
     * the catalog's required-source set changed.
     */
    private boolean requiredRefsMatchProfile(
            List<ResearchRunMethodologySource> existingSources,
            com.keplerops.groundcontrol.domain.research.model.MethodProfile profile) {
        var existingRequiredRefs = existingSources.stream()
                .filter(ResearchRunMethodologySource::isRequired)
                .map(ResearchRunMethodologySource::getSourceRef)
                .sorted()
                .toList();
        var profileRefs = profile.requiredSources().stream()
                .map(com.keplerops.groundcontrol.domain.research.model.MethodProfileSource::ref)
                .sorted()
                .toList();
        return existingRequiredRefs.equals(profileRefs);
    }

    /**
     * GC-RSCH-F006 — record a methodology source on the active selection.
     * Idempotent on sourceRef: if a source with the same ref already exists in
     * the active selection, the existing record is returned unchanged.
     */
    public ResearchRunMethodologySource recordMethodologySource(
            UUID projectId, UUID runId, RecordMethodologySourceCommand cmd) {
        var run = requireRun(projectId, runId);
        requireActive(run);
        if (cmd == null || cmd.sourceRef() == null || cmd.sourceRef().isBlank()) {
            throw new DomainValidationException(
                    "sourceRef must not be blank", INVALID_CODE, Map.of(FIELD, "sourceRef"));
        }
        var sourceRef = cmd.sourceRef().trim();
        requireUnder(sourceRef, SOURCE_REF_MAX, "sourceRef");
        requireUnder(cmd.sourceLabel(), SOURCE_LABEL_MAX, "sourceLabel");

        var selection = methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));

        // Idempotent: same sourceRef in this selection → return existing.
        var existing = methodologySourceRepository.findBySelectionIdAndSourceRef(selection.getId(), sourceRef);
        if (existing.isPresent()) {
            return existing.get();
        }

        var actor = currentActor();
        // Sources recorded via this method are always optional (required=false).
        // Required sources are derived from the selected method's catalog profile
        // and snapshotted at selection (ADR-078), not recorded here.
        var source = new ResearchRunMethodologySource(selection, sourceRef, false, actor);
        source.setSourceLabel(emptyToNull(cmd.sourceLabel()));
        var saved = methodologySourceRepository.save(source);
        log.info(
                "research_run_methodology_source_recorded: project={} run={} source={} required=false",
                run.getProject().getIdentifier(),
                runId,
                saved.getId());
        return saved;
    }

    /**
     * GC-RSCH-F006 — update the state of a methodology source. Idempotent:
     * if already in the target state, the existing record is returned unchanged.
     */
    public ResearchRunMethodologySource updateMethodologySourceState(
            UUID projectId, UUID runId, UUID sourceId, UpdateMethodologySourceStateCommand cmd) {
        var run = requireRun(projectId, runId);
        requireActive(run);
        if (cmd == null || cmd.state() == null) {
            throw new DomainValidationException("state must not be null", INVALID_CODE, Map.of(FIELD, "state"));
        }
        var selection = methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));

        var sources = methodologySourceRepository.findBySelectionId(selection.getId());
        var source = sources.stream()
                .filter(s -> s.getId().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Methodology source not found: " + sourceId));

        if (source.getState() == cmd.state()) {
            return source; // already in target state — idempotent
        }
        if (!source.getState().canTransitionTo(cmd.state())) {
            throw new ConflictException(
                    "Invalid state transition for methodology source: " + source.getState() + " → " + cmd.state(),
                    "research_run_methodology_source_invalid_transition",
                    Map.of(
                            "from", source.getState().name(),
                            "to", cmd.state().name(),
                            "source_ref", source.getSourceRef()));
        }
        source.setState(cmd.state());
        var saved = methodologySourceRepository.save(source);
        log.info(
                "research_run_methodology_source_state_updated: run={} source={} state={}",
                runId,
                sourceId,
                cmd.state());
        return saved;
    }

    /**
     * GC-RSCH-F006 / ADR-078 — the backend-owned methodology catalog: all method
     * profiles with their required primary sources. Global reference data, not
     * project- or run-scoped.
     */
    @Transactional(readOnly = true)
    public List<com.keplerops.groundcontrol.domain.research.model.MethodProfile> listMethodologyCatalog() {
        return methodologyCatalog.allProfiles();
    }

    /** GC-RSCH-F006 — get the active methodology selection for a run. */
    @Transactional(readOnly = true)
    public ResearchRunMethodologySelection getMethodologySelection(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));
    }

    /** GC-RSCH-F006 — list all sources for the active methodology selection (empty if none). */
    @Transactional(readOnly = true)
    public List<ResearchRunMethodologySource> listMethodologySources(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        var selection = methodologySelectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(runId);
        return selection
                .map(s -> methodologySourceRepository.findBySelectionId(s.getId()))
                .orElse(List.of());
    }

    /**
     * GC-RSCH-F006 — enforce that all required methodology sources are in READ
     * state before the METHODOLOGY_REQUIREMENTS artifact can be recorded.
     * <ul>
     *   <li>No active selection → {@link DomainValidationException} with code
     *       {@code research_run_methodology_selection_missing}.</li>
     *   <li>A required source in {@code BLOCKED} state → {@link ConflictException}
     *       with code {@code research_run_methodology_source_blocked}.</li>
     *   <li>Any required source not in {@code READ} state → {@link DomainValidationException}
     *       with code {@code research_run_methodology_sources_incomplete}.</li>
     * </ul>
     */
    private void requireMethodologySourceCoverageComplete(UUID runId) {
        var selection = methodologySelectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(runId);
        if (selection.isEmpty()) {
            throw new DomainValidationException(
                    "A methodology selection is required before recording a METHODOLOGY_REQUIREMENTS artifact",
                    "research_run_methodology_selection_missing",
                    Map.of());
        }
        var sources =
                methodologySourceRepository.findBySelectionId(selection.get().getId());
        var requiredSources = sources.stream()
                .filter(ResearchRunMethodologySource::isRequired)
                .toList();

        // Check for BLOCKED required sources first — these are a distinct conflict.
        var blockedSource = requiredSources.stream()
                .filter(s -> s.getState() == MethodologySourceState.BLOCKED)
                .findFirst();
        if (blockedSource.isPresent()) {
            throw new ConflictException(
                    "Required methodology source is BLOCKED: "
                            + blockedSource.get().getSourceRef(),
                    "research_run_methodology_source_blocked",
                    Map.of("blocked_source_ref", blockedSource.get().getSourceRef()));
        }

        // Any required source not READ blocks the gate.
        var notReadSources = requiredSources.stream()
                .filter(s -> s.getState() != MethodologySourceState.READ)
                .toList();
        if (!notReadSources.isEmpty()) {
            throw new DomainValidationException(
                    "All required methodology sources must be in READ state before recording a METHODOLOGY_REQUIREMENTS artifact",
                    "research_run_methodology_sources_incomplete",
                    Map.of(
                            "blocked_sources",
                            String.valueOf(notReadSources.size()),
                            "first_blocked_ref",
                            notReadSources.get(0).getSourceRef()));
        }
    }

    // ------------------------------------------------------------------
    // Methodology requirements contract (GC-RSCH-F007 / ADR-080)
    // ------------------------------------------------------------------

    /**
     * GC-RSCH-F007 / GC-RSCH-R002 / ADR-080 — record the structured phase-1
     * methodology requirements contract behind the run's ACTIVE {@code
     * METHODOLOGY_REQUIREMENTS} artifact attempt. The chosen method (active
     * selection), artifact id, and attempt are resolved server-side. Exactly one
     * contract exists per artifact attempt; a rework records a new artifact
     * attempt first.
     *
     * <p>Every {@code REQUIREMENT} / {@code METHOD_LIMIT} / {@code NON_CLAIM}
     * entry must link at least one methodology source that belongs to the active
     * selection and is {@code READ} — a claim with no READ source link is never
     * accepted (no model memory as scientific evidence). An {@code
     * OPEN_PROTOCOL_QUESTION} may instead reference another entry in the same
     * contract. Rejected alternatives may point at a {@code METHODOLOGY_CHOICE}
     * rationale entry for the same run.
     */
    public MethodologyRequirementsContractAggregate recordMethodologyRequirementsContract(
            UUID projectId, UUID runId, RecordMethodologyRequirementsContractCommand command) {
        var run = requireRun(projectId, runId);
        requireActive(run);
        if (command == null) {
            throw new DomainValidationException("Contract command must not be null", INVALID_CODE, Map.of());
        }

        // The contract sits behind the ACTIVE METHODOLOGY_REQUIREMENTS artifact.
        var artifact = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new DomainValidationException(
                        "No ACTIVE METHODOLOGY_REQUIREMENTS artifact exists for this run; record the artifact first",
                        "research_run_methodology_artifact_missing",
                        Map.of()));

        // Required methodology sources must be READ before the contract is accepted
        // (ADR-080 §3) — the same gate the artifact recording enforced.
        requireMethodologySourceCoverageComplete(runId);
        var selection = methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));

        // One contract per artifact attempt.
        if (contractRepository.existsByArtifactId(artifact.getId())) {
            throw new ConflictException(
                    "A methodology requirements contract already exists for this artifact attempt",
                    "research_run_methodology_contract_exists",
                    Map.of("artifact_id", artifact.getId().toString()));
        }

        var entryCommands = command.entries();
        if (entryCommands == null || entryCommands.isEmpty()) {
            throw new DomainValidationException(
                    "A methodology requirements contract must have at least one entry",
                    INVALID_CODE,
                    Map.of(FIELD, "entries"));
        }

        // Sources of the active selection, keyed by id, for membership + READ checks.
        var selectionSources = new HashMap<UUID, ResearchRunMethodologySource>();
        for (var s : methodologySourceRepository.findBySelectionId(selection.getId())) {
            selectionSources.put(s.getId(), s);
        }

        var kindByKey = validateContractEntryShape(entryCommands);
        validateContractEntryGrounding(entryCommands, kindByKey, selectionSources);
        var rejectedCommands = command.rejectedAlternatives();
        validateRejectedAlternatives(rejectedCommands, runId);

        return persistContract(run, selection, artifact, entryCommands, rejectedCommands, selectionSources);
    }

    /**
     * First pass over the entries: validates shape (kind/entryKey/statement) and
     * returns the kind-by-key map needed to resolve {@code OPEN_PROTOCOL_QUESTION}
     * references in {@link #validateContractEntryGrounding}.
     */
    private Map<String, ContractEntryKind> validateContractEntryShape(
            List<RecordMethodologyRequirementsContractCommand.EntryCommand> entryCommands) {
        var entryKeys = new HashSet<String>();
        var kindByKey = new HashMap<String, ContractEntryKind>();
        for (var e : entryCommands) {
            if (e == null || e.kind() == null) {
                throw new DomainValidationException("entry kind must not be null", INVALID_CODE, Map.of(FIELD, "kind"));
            }
            var key = emptyToNull(e.entryKey());
            if (key == null) {
                throw new DomainValidationException(
                        "entryKey must not be blank", INVALID_CODE, Map.of(FIELD, CONTRACT_ENTRY_KEY_FIELD));
            }
            requireUnder(key, ENTRY_KEY_MAX, CONTRACT_ENTRY_KEY_FIELD);
            if (!entryKeys.add(key)) {
                throw new DomainValidationException(
                        "Duplicate entryKey in contract: " + key,
                        "research_run_methodology_contract_duplicate_entry_key",
                        Map.of(CONTRACT_ENTRY_KEY_FIELD, key));
            }
            kindByKey.put(key, e.kind());
            if (emptyToNull(e.statement()) == null) {
                throw new DomainValidationException(
                        "statement must not be blank", INVALID_CODE, Map.of(FIELD, "statement"));
            }
            requireUnder(e.statement(), STATEMENT_MAX, "statement");
        }
        return kindByKey;
    }

    /** Second pass over the entries: validates grounding (source links / references). */
    private void validateContractEntryGrounding(
            List<RecordMethodologyRequirementsContractCommand.EntryCommand> entryCommands,
            Map<String, ContractEntryKind> kindByKey,
            Map<UUID, ResearchRunMethodologySource> selectionSources) {
        for (var e : entryCommands) {
            var links = e.sourceLinks();
            var hasLinks = links != null && !links.isEmpty();
            var reference = emptyToNull(e.referencesEntryKey());
            requireEntryGroundingPresent(e, hasLinks, reference);
            if (reference != null) {
                validateEntryReference(e, reference, kindByKey);
            }
            if (hasLinks) {
                validateEntrySourceLinks(e, links, selectionSources);
            }
        }
    }

    private void requireEntryGroundingPresent(
            RecordMethodologyRequirementsContractCommand.EntryCommand e, boolean hasLinks, String reference) {
        if (e.kind().requiresSourceGrounding()) {
            if (!hasLinks) {
                throw new DomainValidationException(
                        e.kind() + " entry '" + key(e) + "' must link at least one methodology source",
                        "research_run_methodology_contract_entry_ungrounded",
                        Map.of(
                                CONTRACT_ENTRY_KEY_FIELD,
                                key(e),
                                "kind",
                                e.kind().name()));
            }
        } else if (!hasLinks && reference == null) {
            throw new DomainValidationException(
                    "OPEN_PROTOCOL_QUESTION entry '" + key(e) + "' must link a source or reference another entry",
                    "research_run_methodology_contract_open_question_unlinked",
                    Map.of(CONTRACT_ENTRY_KEY_FIELD, key(e)));
        }
    }

    private void validateEntryReference(
            RecordMethodologyRequirementsContractCommand.EntryCommand e,
            String reference,
            Map<String, ContractEntryKind> kindByKey) {
        if (reference.equals(key(e))) {
            throw new DomainValidationException(
                    "entry '" + key(e) + "' may not reference itself",
                    "research_run_methodology_contract_self_reference",
                    Map.of(REFERENCES_ENTRY_KEY_FIELD, reference));
        }
        var referencedKind = kindByKey.get(reference);
        if (referencedKind == null) {
            throw new DomainValidationException(
                    "referencesEntryKey '" + reference + "' does not match any entry in this contract",
                    "research_run_methodology_contract_bad_reference",
                    Map.of(REFERENCES_ENTRY_KEY_FIELD, reference));
        }
        // ADR-080 §3: a reference must resolve to a source-grounded entry
        // (REQUIREMENT / METHOD_LIMIT / NON_CLAIM), never to another
        // OPEN_PROTOCOL_QUESTION — otherwise an unlinked question could chain
        // to another question and enter phase 2 with no source grounding.
        if (!referencedKind.requiresSourceGrounding()) {
            throw new DomainValidationException(
                    "referencesEntryKey '" + reference
                            + "' must target a source-grounded entry (REQUIREMENT, METHOD_LIMIT, or NON_CLAIM)",
                    "research_run_methodology_contract_reference_not_grounded",
                    Map.of(REFERENCES_ENTRY_KEY_FIELD, reference, "referenced_kind", referencedKind.name()));
        }
    }

    private void validateEntrySourceLinks(
            RecordMethodologyRequirementsContractCommand.EntryCommand e,
            List<RecordMethodologyRequirementsContractCommand.SourceLinkCommand> links,
            Map<UUID, ResearchRunMethodologySource> selectionSources) {
        var seenSources = new HashSet<UUID>();
        for (var link : links) {
            if (link == null || link.sourceId() == null) {
                throw new DomainValidationException(
                        "source link sourceId must not be null", INVALID_CODE, Map.of(FIELD, "sourceId"));
            }
            if (!seenSources.add(link.sourceId())) {
                throw new DomainValidationException(
                        "Duplicate source link within entry '" + key(e) + "'",
                        "research_run_methodology_contract_duplicate_source_link",
                        Map.of(SOURCE_ID_FIELD, link.sourceId().toString()));
            }
            var source = selectionSources.get(link.sourceId());
            if (source == null) {
                throw new DomainValidationException(
                        "Source link target is not a source of the active methodology selection",
                        "research_run_methodology_contract_source_not_in_selection",
                        Map.of(SOURCE_ID_FIELD, link.sourceId().toString()));
            }
            if (source.getState() != MethodologySourceState.READ) {
                throw new DomainValidationException(
                        "Source link target must be READ before it can ground a contract entry",
                        "research_run_methodology_contract_source_not_read",
                        Map.of(
                                SOURCE_ID_FIELD,
                                link.sourceId().toString(),
                                "state",
                                source.getState().name()));
            }
            requireUnder(link.locator(), LOCATOR_MAX, LOCATOR_FIELD);
        }
    }

    /** Validates rejected alternatives: shape, catalog membership, and rationale linkage. */
    private void validateRejectedAlternatives(
            List<RecordMethodologyRequirementsContractCommand.RejectedAlternativeCommand> rejectedCommands,
            UUID runId) {
        if (rejectedCommands == null) {
            return;
        }
        for (var r : rejectedCommands) {
            if (r == null || emptyToNull(r.methodKey()) == null) {
                throw new DomainValidationException(
                        "rejected alternative methodKey must not be blank",
                        INVALID_CODE,
                        Map.of(FIELD, METHOD_KEY_FIELD));
            }
            requireUnder(r.methodKey(), METHOD_KEY_MAX, METHOD_KEY_FIELD);
            requireUnder(r.profileVersion(), PROFILE_VERSION_MAX, "profileVersion");
            // ADR-080 §2: a non-external rejected alternative claims a catalog
            // method and must resolve against the backend MethodologyCatalog. An
            // unknown method must instead be recorded through the external/manual path.
            if (!r.external()
                    && methodologyCatalog.findProfile(r.methodKey().trim()).isEmpty()) {
                throw new DomainValidationException(
                        "rejected alternative method '" + r.methodKey().trim()
                                + "' is not in the methodology catalog; unknown methods must be recorded as external",
                        "research_run_methodology_contract_rejected_alternative_unknown_method",
                        Map.of("method_key", r.methodKey().trim()));
            }
            if (r.rationaleEntryId() != null) {
                requireRejectedAlternativeRationale(r, runId);
            }
        }
    }

    private void requireRejectedAlternativeRationale(
            RecordMethodologyRequirementsContractCommand.RejectedAlternativeCommand r, UUID runId) {
        var rationale = rationaleRepository
                .findById(r.rationaleEntryId())
                .filter(entry -> entry.getResearchRun().getId().equals(runId))
                .orElseThrow(() -> new DomainValidationException(
                        "rejected alternative rationale entry not found for this run",
                        "research_run_methodology_contract_rationale_not_found",
                        Map.of("rationale_entry_id", r.rationaleEntryId().toString())));
        if (rationale.getKind() != RationaleEntryKind.METHODOLOGY_CHOICE) {
            throw new DomainValidationException(
                    "rejected alternative rationale entry must be of kind METHODOLOGY_CHOICE",
                    "research_run_methodology_contract_rationale_wrong_kind",
                    Map.of("kind", rationale.getKind().name()));
        }
    }

    /** Persists the contract aggregate (contract, entries, source links, rejected alternatives). */
    private MethodologyRequirementsContractAggregate persistContract(
            ResearchRun run,
            ResearchRunMethodologySelection selection,
            ResearchRunArtifact artifact,
            List<RecordMethodologyRequirementsContractCommand.EntryCommand> entryCommands,
            List<RecordMethodologyRequirementsContractCommand.RejectedAlternativeCommand> rejectedCommands,
            Map<UUID, ResearchRunMethodologySource> selectionSources) {
        var actor = currentActor();
        var contract = new MethodologyRequirementsContract(
                run, selection, artifact.getId(), artifact.getAttemptNo(), CONTRACT_SCHEMA_VERSION, actor);
        var savedContract = contractRepository.save(contract);

        var savedEntries = new ArrayList<MethodologyRequirementsContractEntry>();
        var savedLinks = new ArrayList<MethodologyRequirementsContractEntrySourceLink>();
        for (var e : entryCommands) {
            var entry = new MethodologyRequirementsContractEntry(
                    savedContract, e.kind(), key(e), e.statement().trim(), emptyToNull(e.referencesEntryKey()), actor);
            var savedEntry = contractEntryRepository.save(entry);
            savedEntries.add(savedEntry);
            if (e.sourceLinks() != null) {
                for (var link : e.sourceLinks()) {
                    var source = selectionSources.get(link.sourceId());
                    savedLinks.add(
                            contractEntrySourceLinkRepository.save(new MethodologyRequirementsContractEntrySourceLink(
                                    savedEntry, source, emptyToNull(link.locator()))));
                }
            }
        }

        var savedRejected = new ArrayList<MethodologyRequirementsContractRejectedAlternative>();
        if (rejectedCommands != null) {
            for (var r : rejectedCommands) {
                savedRejected.add(contractRejectedAlternativeRepository.save(
                        new MethodologyRequirementsContractRejectedAlternative(
                                savedContract,
                                r.rationaleEntryId(),
                                r.methodKey().trim(),
                                emptyToNull(r.profileVersion()),
                                r.external())));
            }
        }

        log.info(
                "research_run_methodology_contract_recorded: project={} run={} artifact={} attempt={} entries={} links={} rejected={}",
                run.getProject().getIdentifier(),
                run.getId(),
                artifact.getId(),
                artifact.getAttemptNo(),
                savedEntries.size(),
                savedLinks.size(),
                savedRejected.size());

        return new MethodologyRequirementsContractAggregate(savedContract, savedEntries, savedLinks, savedRejected);
    }

    /**
     * GC-RSCH-F008 / ADR-080 §5 — read the active methodology requirements
     * contract (the surface protocol planning consumes as its contract). Resolves
     * the ACTIVE {@code METHODOLOGY_REQUIREMENTS} artifact, then the contract tied
     * to that attempt, and bundles its entries, source links, and rejected
     * alternatives. {@link NotFoundException} when no contract has been recorded.
     */
    @Transactional(readOnly = true)
    public MethodologyRequirementsContractAggregate getMethodologyRequirementsContract(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        var artifact = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("No methodology requirements contract for run " + runId));
        var contract = contractRepository
                .findByArtifactId(artifact.getId())
                .orElseThrow(() -> new NotFoundException("No methodology requirements contract for run " + runId));
        var entries = contractEntryRepository.findByContractIdOrderByCreatedAtAsc(contract.getId());
        var links = contractEntrySourceLinkRepository.findByEntryContractIdOrderByCreatedAtAsc(contract.getId());
        var rejected = contractRejectedAlternativeRepository.findByContractIdOrderByCreatedAtAsc(contract.getId());
        return new MethodologyRequirementsContractAggregate(contract, entries, links, rejected);
    }

    private static String key(RecordMethodologyRequirementsContractCommand.EntryCommand e) {
        return e.entryKey().trim();
    }

    // ------------------------------------------------------------------
    // Protocol plan (GC-RSCH-F008 / GC-RSCH-F009 / ADR-081)
    // ------------------------------------------------------------------

    /**
     * GC-RSCH-F008 / GC-RSCH-F009 / ADR-081 — record the structured protocol
     * plan behind the run's ACTIVE {@code PROTOCOL_PLAN} artifact attempt,
     * answering the run's one active ADR-080 methodology requirements contract.
     * Every current {@code REQUIREMENT} / {@code OPEN_PROTOCOL_QUESTION}
     * contract entry must have exactly one coverage disposition; {@code
     * METHOD_LIMIT} / {@code NON_CLAIM} entries are constraints the plan
     * carries forward, not coverable answers (ADR-081 §2). The plan must also
     * include every section kind the selected method profile requires ({@link
     * ProtocolMethodShape}); a source role may only be assigned on a {@code
     * SOURCE_ROLES} section of the taxonomy-development method (ADR-081 §3).
     */
    public ProtocolPlanAggregate recordProtocolPlan(UUID projectId, UUID runId, RecordProtocolPlanCommand command) {
        var run = requireRun(projectId, runId);
        requireActive(run);
        if (command == null) {
            throw new DomainValidationException("Protocol plan command must not be null", INVALID_CODE, Map.of());
        }
        var schemaVersion = emptyToNull(command.protocolSchemaVersion());
        if (schemaVersion == null) {
            throw new DomainValidationException(
                    "protocolSchemaVersion must not be blank", INVALID_CODE, Map.of(FIELD, "protocolSchemaVersion"));
        }
        requireUnder(schemaVersion, PROTOCOL_SCHEMA_VERSION_MAX, "protocolSchemaVersion");

        // The plan sits behind the ACTIVE PROTOCOL_PLAN artifact.
        var artifact = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.PROTOCOL_PLAN, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new DomainValidationException(
                        "No ACTIVE PROTOCOL_PLAN artifact exists for this run; record the artifact first",
                        "research_run_protocol_plan_artifact_missing",
                        Map.of()));

        // One plan per artifact attempt.
        if (protocolPlanRepository.existsByArtifactId(artifact.getId())) {
            throw new ConflictException(
                    "A protocol plan already exists for this artifact attempt",
                    "research_run_protocol_plan_exists",
                    Map.of("artifact_id", artifact.getId().toString()));
        }

        // The plan answers the run's one active ADR-080 methodology requirements contract.
        var methodologyArtifact = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new DomainValidationException(
                        "No ACTIVE METHODOLOGY_REQUIREMENTS artifact exists for this run",
                        "research_run_methodology_artifact_missing",
                        Map.of()));
        var contract = contractRepository
                .findByArtifactId(methodologyArtifact.getId())
                .orElseThrow(() -> new DomainValidationException(
                        "No methodology requirements contract has been recorded for this run; record it first",
                        "research_run_protocol_plan_contract_missing",
                        Map.of()));
        var contractEntries = contractEntryRepository.findByContractIdOrderByCreatedAtAsc(contract.getId());

        var selection = methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));

        validateProtocolPlanCoverage(command.coverages(), contractEntries);
        validateProtocolPlanSections(command.sections(), selection.getMethodKey());

        return persistProtocolPlan(run, contract, artifact, selection, schemaVersion, command);
    }

    /**
     * First validation pass: every {@code REQUIREMENT} / {@code
     * OPEN_PROTOCOL_QUESTION} contract entry has exactly one coverage, no
     * unknown/duplicate {@code contractEntryKey} is present, and no coverage
     * targets a {@code METHOD_LIMIT} / {@code NON_CLAIM} entry.
     */
    private void validateProtocolPlanCoverage(
            List<RecordProtocolPlanCommand.CoverageCommand> coverageCommands,
            List<MethodologyRequirementsContractEntry> contractEntries) {
        var kindByKey = new HashMap<String, ContractEntryKind>();
        var coverableKeys = new HashSet<String>();
        for (var entry : contractEntries) {
            kindByKey.put(entry.getEntryKey(), entry.getKind());
            if (entry.getKind() == ContractEntryKind.REQUIREMENT
                    || entry.getKind() == ContractEntryKind.OPEN_PROTOCOL_QUESTION) {
                coverableKeys.add(entry.getEntryKey());
            }
        }
        var seenKeys = new HashSet<String>();
        var commands =
                coverageCommands == null ? List.<RecordProtocolPlanCommand.CoverageCommand>of() : coverageCommands;
        for (var c : commands) {
            if (c == null || emptyToNull(c.contractEntryKey()) == null) {
                throw new DomainValidationException(
                        "contractEntryKey must not be blank",
                        INVALID_CODE,
                        Map.of(FIELD, CONTRACT_ENTRY_KEY_JSON_FIELD));
            }
            var key = c.contractEntryKey().trim();
            requireUnder(key, ENTRY_KEY_MAX, CONTRACT_ENTRY_KEY_JSON_FIELD);
            if (!seenKeys.add(key)) {
                throw new DomainValidationException(
                        "Duplicate protocol plan coverage for contract entry: " + key,
                        "research_run_protocol_plan_duplicate_coverage",
                        Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
            }
            var kind = kindByKey.get(key);
            if (kind == null) {
                throw new DomainValidationException(
                        "contractEntryKey '" + key
                                + "' does not match any entry in the active methodology requirements contract",
                        "research_run_protocol_plan_unknown_contract_entry",
                        Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
            }
            if (kind == ContractEntryKind.METHOD_LIMIT || kind == ContractEntryKind.NON_CLAIM) {
                throw new DomainValidationException(
                        "contractEntryKey '" + key + "' is a " + kind
                                + "; it is a constraint the plan carries forward, not a coverable answer",
                        "research_run_protocol_plan_entry_not_coverable",
                        Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key, "kind", kind.name()));
            }
            if (c.disposition() == null) {
                throw new DomainValidationException(
                        "disposition must not be null", INVALID_CODE, Map.of(FIELD, DISPOSITION_FIELD));
            }
            validateCoverageDispositionFields(c, key);
        }
        if (!seenKeys.containsAll(coverableKeys)) {
            var missing = new HashSet<>(coverableKeys);
            missing.removeAll(seenKeys);
            throw new DomainValidationException(
                    "Protocol plan coverage is missing entries: " + missing,
                    "research_run_protocol_plan_coverage_incomplete",
                    Map.of("missing_entry_keys", String.join(",", missing)));
        }
    }

    /** Second validation pass: the fields a disposition requires are present and bounded (ADR-081 §2). */
    private void validateCoverageDispositionFields(RecordProtocolPlanCommand.CoverageCommand c, String key) {
        requireUnder(c.answerSummary(), ANSWER_SUMMARY_MAX, "answerSummary");
        requireUnder(c.rationale(), PROTOCOL_RATIONALE_MAX, "rationale");
        requireUnder(c.decisionReference(), DECISION_REFERENCE_MAX, "decisionReference");
        switch (c.disposition()) {
            case FILLED -> {
                if (c.answerProvenance() == null || emptyToNull(c.answerSummary()) == null) {
                    throw new DomainValidationException(
                            "FILLED coverage for '" + key + "' requires answerProvenance and answerSummary",
                            "research_run_protocol_plan_filled_incomplete",
                            Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
                }
            }
            case DEFERRED_NON_BLOCKING -> {
                if (c.deferredToStage() == null || emptyToNull(c.rationale()) == null) {
                    throw new DomainValidationException(
                            "DEFERRED_NON_BLOCKING coverage for '" + key + "' requires deferredToStage and rationale",
                            "research_run_protocol_plan_deferred_incomplete",
                            Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
                }
            }
            case NOT_APPLICABLE_WITH_RATIONALE -> {
                if (emptyToNull(c.rationale()) == null) {
                    throw new DomainValidationException(
                            "NOT_APPLICABLE_WITH_RATIONALE coverage for '" + key + "' requires rationale",
                            "research_run_protocol_plan_not_applicable_incomplete",
                            Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
                }
            }
            case RESOLVED_BY_USER_DECISION -> {
                if (emptyToNull(c.decisionReference()) == null && emptyToNull(c.rationale()) == null) {
                    throw new DomainValidationException(
                            "RESOLVED_BY_USER_DECISION coverage for '" + key
                                    + "' requires decisionReference or rationale",
                            "research_run_protocol_plan_resolved_incomplete",
                            Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
                }
            }
            case BLOCKING_DECISION_REQUIRED -> {
                if (emptyToNull(c.rationale()) == null) {
                    throw new DomainValidationException(
                            "BLOCKING_DECISION_REQUIRED coverage for '" + key + "' requires rationale",
                            "research_run_protocol_plan_blocking_incomplete",
                            Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
                }
            }
            default -> throw new IllegalStateException("Unhandled coverage disposition: " + c.disposition());
        }
    }

    /**
     * Third validation pass: every section kind the selected method profile
     * requires is present ({@link ProtocolMethodShape}), section keys are
     * unique, and {@code sourceRole} is only assigned on a {@code
     * SOURCE_ROLES} section of the taxonomy-development method (ADR-081 §3).
     */
    private void validateProtocolPlanSections(
            List<RecordProtocolPlanCommand.SectionCommand> sectionCommands, String methodKey) {
        if (sectionCommands == null || sectionCommands.isEmpty()) {
            throw new DomainValidationException(
                    "Protocol plan must include at least one section", INVALID_CODE, Map.of(FIELD, "sections"));
        }
        var seenKeys = new HashSet<String>();
        var presentKinds = EnumSet.noneOf(ProtocolSectionKind.class);
        var isTaxonomy = ProtocolMethodShape.isTaxonomyDevelopment(methodKey);
        var taxonomySourceRoles = EnumSet.noneOf(ProtocolSourceRole.class);
        for (var s : sectionCommands) {
            if (s == null || emptyToNull(s.sectionKey()) == null) {
                throw new DomainValidationException(
                        "sectionKey must not be blank", INVALID_CODE, Map.of(FIELD, SECTION_KEY_JSON_FIELD));
            }
            var key = s.sectionKey().trim();
            requireUnder(key, SECTION_KEY_MAX, SECTION_KEY_JSON_FIELD);
            if (!seenKeys.add(key)) {
                throw new DomainValidationException(
                        "Duplicate protocol plan sectionKey: " + key,
                        "research_run_protocol_plan_duplicate_section_key",
                        Map.of(SECTION_KEY_JSON_FIELD, key));
            }
            if (s.sectionKind() == null) {
                throw new DomainValidationException(
                        "sectionKind must not be null", INVALID_CODE, Map.of(FIELD, SECTION_KIND_FIELD));
            }
            if (emptyToNull(s.contentSummary()) == null) {
                throw new DomainValidationException(
                        "contentSummary must not be blank", INVALID_CODE, Map.of(FIELD, "contentSummary"));
            }
            requireUnder(s.contentSummary(), SUMMARY_MAX, "contentSummary");
            var isTaxonomySourceRoleSection = isTaxonomy && s.sectionKind() == ProtocolSectionKind.SOURCE_ROLES;
            if (s.sourceRole() != null && !isTaxonomySourceRoleSection) {
                throw new DomainValidationException(
                        "sourceRole is only permitted on SOURCE_ROLES sections of the taxonomy-development method",
                        "research_run_protocol_plan_source_role_not_allowed",
                        Map.of(SECTION_KEY_JSON_FIELD, key, METHOD_KEY_FIELD, methodKey == null ? "" : methodKey));
            }
            // ADR-081 §3 — taxonomy source-role separation is the hard boundary case: a
            // SOURCE_ROLES section must name the role it carries so background/framing,
            // methodology, and validation material cannot collapse into the taxonomy corpus.
            if (isTaxonomySourceRoleSection) {
                if (s.sourceRole() == null) {
                    throw new DomainValidationException(
                            "A taxonomy-development SOURCE_ROLES section must declare a sourceRole",
                            "research_run_protocol_plan_source_role_required",
                            Map.of(SECTION_KEY_JSON_FIELD, key));
                }
                taxonomySourceRoles.add(s.sourceRole());
            }
            presentKinds.add(s.sectionKind());
        }
        var required = ProtocolMethodShape.requiredSections(methodKey);
        if (!presentKinds.containsAll(required)) {
            var missing = EnumSet.copyOf(required);
            missing.removeAll(presentKinds);
            throw new DomainValidationException(
                    "Protocol plan is missing required sections for method '" + methodKey + "': " + missing,
                    "research_run_protocol_plan_section_missing",
                    Map.of(
                            METHOD_KEY_FIELD,
                            methodKey == null ? "" : methodKey,
                            "missing_section_kinds",
                            missing.toString()));
        }
        // ADR-081 §3 — the accepted taxonomy plan must actually carry every distinct
        // source role, not merely permit them; otherwise later stages cannot rely on the
        // plan to keep background sources from supporting taxonomy claims.
        if (isTaxonomy) {
            var requiredRoles = EnumSet.allOf(ProtocolSourceRole.class);
            if (!taxonomySourceRoles.containsAll(requiredRoles)) {
                var missingRoles = EnumSet.copyOf(requiredRoles);
                missingRoles.removeAll(taxonomySourceRoles);
                throw new DomainValidationException(
                        "Taxonomy-development protocol plan must separate all source roles across SOURCE_ROLES"
                                + " sections; missing: " + missingRoles,
                        "research_run_protocol_plan_source_roles_incomplete",
                        Map.of(
                                METHOD_KEY_FIELD,
                                methodKey == null ? "" : methodKey,
                                "missing_source_roles",
                                missingRoles.toString()));
            }
        }
    }

    /** Persists the plan aggregate (plan, coverage rows, section rows) in one transaction. */
    private ProtocolPlanAggregate persistProtocolPlan(
            ResearchRun run,
            MethodologyRequirementsContract contract,
            ResearchRunArtifact artifact,
            ResearchRunMethodologySelection selection,
            String schemaVersion,
            RecordProtocolPlanCommand command) {
        var actor = currentActor();
        var plan = new ProtocolPlan(
                run,
                contract,
                artifact.getId(),
                artifact.getAttemptNo(),
                schemaVersion,
                selection.getMethodKey(),
                selection.getProfileVersion(),
                actor);
        var savedPlan = protocolPlanRepository.save(plan);

        var savedCoverages = new ArrayList<ProtocolPlanCoverage>();
        if (command.coverages() != null) {
            for (var c : command.coverages()) {
                savedCoverages.add(protocolPlanCoverageRepository.save(new ProtocolPlanCoverage(
                        savedPlan,
                        c.contractEntryKey().trim(),
                        c.disposition(),
                        emptyToNull(c.answerSummary()),
                        c.answerProvenance(),
                        emptyToNull(c.rationale()),
                        c.deferredToStage(),
                        emptyToNull(c.decisionReference()),
                        actor)));
            }
        }

        var savedSections = new ArrayList<ProtocolPlanSection>();
        for (var s : command.sections()) {
            savedSections.add(protocolPlanSectionRepository.save(new ProtocolPlanSection(
                    savedPlan,
                    s.sectionKey().trim(),
                    s.sectionKind(),
                    s.sourceRole(),
                    s.contentSummary().trim(),
                    actor)));
        }

        log.info(
                "research_run_protocol_plan_recorded: project={} run={} artifact={} attempt={} coverages={} sections={}",
                run.getProject().getIdentifier(),
                run.getId(),
                artifact.getId(),
                artifact.getAttemptNo(),
                savedCoverages.size(),
                savedSections.size());

        return new ProtocolPlanAggregate(savedPlan, savedCoverages, savedSections);
    }

    /**
     * GC-RSCH-F008 — read the active protocol plan (the surface source search
     * and later stages consume). Resolves the ACTIVE {@code PROTOCOL_PLAN}
     * artifact, then the plan tied to that attempt, and bundles its coverage
     * and section rows. {@link NotFoundException} when no plan has been
     * recorded.
     */
    @Transactional(readOnly = true)
    public ProtocolPlanAggregate getProtocolPlan(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        var artifact = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.PROTOCOL_PLAN, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("No protocol plan for run " + runId));
        var plan = protocolPlanRepository
                .findByArtifactId(artifact.getId())
                .orElseThrow(() -> new NotFoundException("No protocol plan for run " + runId));
        var coverages = protocolPlanCoverageRepository.findByProtocolPlanId(plan.getId());
        var sections = protocolPlanSectionRepository.findByProtocolPlanId(plan.getId());
        return new ProtocolPlanAggregate(plan, coverages, sections);
    }

    /**
     * GC-RSCH-F008 / ADR-081 §2 — the {@code SOURCE_SEARCH} durable gate: an
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

    @Transactional(readOnly = true)
    public List<ResearchRunGateDecisionLog> listGateDecisionLog(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return decisionLogRepository.findByResearchRunIdOrderByDecidedAtAsc(runId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunReviewComment> listReviewComments(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return reviewCommentRepository.findByResearchRunIdOrderByCreatedAtAsc(runId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunRationaleEntry> listRationale(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return rationaleRepository.findByResearchRunIdOrderByRecordedAtAsc(runId);
    }

    @Transactional(readOnly = true)
    public ResearchRunDisclosure getDisclosure(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return disclosureRepository
                .findFirstByResearchRunIdAndStatus(runId, DisclosureStatus.CURRENT)
                .orElseThrow(() -> new NotFoundException("No current disclosure for run " + runId));
    }

    @Transactional(readOnly = true)
    public List<ResearchRunDisclosureEntry> listDisclosureEntries(UUID projectId, UUID runId, UUID disclosureId) {
        requireRun(projectId, runId);
        var disclosure = disclosureRepository
                .findById(disclosureId)
                .filter(d -> d.getResearchRun().getId().equals(runId))
                .orElseThrow(() -> new NotFoundException("Disclosure not found: " + disclosureId));
        return disclosureEntryRepository.findByDisclosureId(disclosure.getId());
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
