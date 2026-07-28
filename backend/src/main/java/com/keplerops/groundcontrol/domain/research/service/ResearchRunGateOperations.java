package com.keplerops.groundcontrol.domain.research.service;

import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.ACTION_ID_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.GATE_POINT;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.INVALID_CODE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.OPTION_ID_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.QUESTION_KEY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.RATIONALE_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.RATIONALE_SUMMARY;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.RECOMMENDATION_SUMMARY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.currentActor;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.emptyToNull;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.log;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireUnder;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGate;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateDecisionLogRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Gate resolution and the decision log (ADR-065).
 *
 * Split out of {@link ResearchRunService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class ResearchRunGateOperations {

    private final ResearchRunRepository runRepository;
    private final ResearchRunGateRepository gateRepository;
    private final ResearchRunGateDecisionLogRepository decisionLogRepository;
    private final ResearchRunService service;

    ResearchRunGateOperations(
            ResearchRunRepository runRepository,
            ResearchRunGateRepository gateRepository,
            ResearchRunGateDecisionLogRepository decisionLogRepository,
            ResearchRunService service) {
        this.runRepository = runRepository;
        this.gateRepository = gateRepository;
        this.decisionLogRepository = decisionLogRepository;
        this.service = service;
    }

    // ------------------------------------------------------------------
    // Lifecycle: gates
    // ------------------------------------------------------------------

    /** GC-RSCH-R003 — record a durable decision for a run gate. */
    ResearchRunGate resolveGate(UUID projectId, UUID runId, GateDecisionCommand command) {
        var run = service.requireRun(projectId, runId);
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

    private void appendDecisionLog(ResearchRun run, GateDecisionCommand command, String actor) {
        var gatePoint = command.gatePoint();
        var entry = new ResearchRunGateDecisionLog(
                run, gatePoint, gatePoint.guardedStageExit(), command.outcome(), actor, Instant.now());
        entry.setArtifactAttemptNo(service.activeAttemptForGate(run, gatePoint));
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
}
