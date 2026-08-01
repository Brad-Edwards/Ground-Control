package com.keplerops.groundcontrol.domain.research.service;

import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.CURRENT_STAGE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.HASH_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.IDEMPOTENCY_KEY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.INVALID_CODE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.LOCATOR_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.LOCATOR_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.currentActor;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.emptyToNull;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.log;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireActive;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireUnder;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import java.util.Map;
import java.util.UUID;

/**
 * Artifact manifest recording and its derived counts (ADR-064).
 *
 * Split out of {@link ResearchRunService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class ResearchRunArtifactOperations {

    private final ResearchRunRepository runRepository;
    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunGateRepository gateRepository;
    private final ResearchRunDisclosureRepository disclosureRepository;
    private final ResearchRunService service;

    ResearchRunArtifactOperations(
            ResearchRunRepository runRepository,
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunGateRepository gateRepository,
            ResearchRunDisclosureRepository disclosureRepository,
            ResearchRunService service) {
        this.service = service;
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.gateRepository = gateRepository;
        this.disclosureRepository = disclosureRepository;
    }

    // ------------------------------------------------------------------
    // Lifecycle: artifacts (checkpoint authority)
    // ------------------------------------------------------------------

    /**
     * GC-RSCH-F003/F036 — record (or rework) the current stage's output artifact.
     * Idempotent on {@code idempotencyKey}; a rework supersedes the prior ACTIVE
     * record and re-opens the stage's guarding gate.
     */
    ResearchRunArtifact recordArtifact(UUID projectId, UUID runId, RecordArtifactCommand command) {
        var run = service.requireRun(projectId, runId);
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
            service.requireMethodologySourceCoverageComplete(runId);
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
        artifact.setDataClass(command.dataClass());
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

    private void staleCurrentDisclosure(ResearchRun run) {
        disclosureRepository
                .findFirstByResearchRunIdAndStatus(run.getId(), DisclosureStatus.CURRENT)
                .ifPresent(disclosure -> {
                    disclosure.markStale();
                    disclosureRepository.save(disclosure);
                    log.info("research_run_disclosure_staled: run={} disclosure={}", run.getId(), disclosure.getId());
                });
    }
}
