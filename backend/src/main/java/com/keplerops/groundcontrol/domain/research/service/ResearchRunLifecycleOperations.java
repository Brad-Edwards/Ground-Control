package com.keplerops.groundcontrol.domain.research.service;

import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.CURRENT_STAGE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.ERROR_CLASS_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.ERROR_CODE_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.ERROR_SUMMARY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.emptyToNull;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.log;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireUnder;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.research.model.DisclosureEntryFamily;
import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateDecisionLogRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Run lifecycle transitions and usage accounting.
 *
 * Split out of {@link ResearchRunService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class ResearchRunLifecycleOperations {

    private final ResearchRunRepository runRepository;
    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunGateDecisionLogRepository decisionLogRepository;
    private final ResearchRunDisclosureRepository disclosureRepository;
    private final ResearchRunDisclosureEntryRepository disclosureEntryRepository;
    private final ResearchRunService service;

    ResearchRunLifecycleOperations(
            ResearchRunRepository runRepository,
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunGateDecisionLogRepository decisionLogRepository,
            ResearchRunDisclosureRepository disclosureRepository,
            ResearchRunDisclosureEntryRepository disclosureEntryRepository,
            ResearchRunService service) {
        this.service = service;
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.decisionLogRepository = decisionLogRepository;
        this.disclosureRepository = disclosureRepository;
        this.disclosureEntryRepository = disclosureEntryRepository;
    }

    // ------------------------------------------------------------------
    // Lifecycle: stop / fail / resume / usage
    // ------------------------------------------------------------------

    /** GC-RSCH-F036 — stop an active run; resumable later. */
    ResearchRun stop(UUID projectId, UUID runId) {
        var run = service.requireRun(projectId, runId);
        run.transitionStatus(ResearchRunStatus.STOPPED);
        run.setStoppedAt(Instant.now());
        var saved = runRepository.save(run);
        log.info("research_run_stopped: project={} run={}", run.getProject().getIdentifier(), runId);
        return saved;
    }

    /** GC-RSCH-N007 — fail an active run with a bounded failure observation. */
    ResearchRun fail(UUID projectId, UUID runId, FailRunCommand command) {
        var run = service.requireRun(projectId, runId);
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
    ResearchRun resume(UUID projectId, UUID runId) {
        var run = service.requireRun(projectId, runId);
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
    ResearchRun recordUsage(UUID projectId, UUID runId, long tokens, long costUsdMicros) {
        var run = service.requireRun(projectId, runId);
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
    ResearchRun complete(UUID projectId, UUID runId) {
        var run = service.requireRun(projectId, runId);
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
}
