package com.keplerops.groundcontrol.domain.research.service;

import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.BODY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.CONFIDENCE_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.GATE_POINT;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.INVALID_CODE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.LOCATOR_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.LOCATOR_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.MODEL_LABEL_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.RATIONALE_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.RATIONALE_SUMMARY;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SECTION_KEY_JSON_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SECTION_KEY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SUBJECT_KEY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SUMMARY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.TARGET_ARTIFACT_ID;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.TARGET_DECISION_LOG_ID;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.TARGET_STAGE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.currentActor;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.emptyToNull;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.log;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireUnder;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.research.model.DisclosureEntryFamily;
import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosure;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosureEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunRationaleEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentTarget;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateDecisionLogRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRationaleEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunReviewCommentRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Review comments, rationale entries and disclosures (ADR-066/067/068).
 *
 * Split out of {@link ResearchRunService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class ResearchRunDecisionSurfaceOperations {

    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunGateDecisionLogRepository decisionLogRepository;
    private final ResearchRunReviewCommentRepository reviewCommentRepository;
    private final ResearchRunRationaleEntryRepository rationaleRepository;
    private final ResearchRunDisclosureRepository disclosureRepository;
    private final ResearchRunDisclosureEntryRepository disclosureEntryRepository;
    private final ResearchRunService service;

    ResearchRunDecisionSurfaceOperations(
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunGateDecisionLogRepository decisionLogRepository,
            ResearchRunReviewCommentRepository reviewCommentRepository,
            ResearchRunRationaleEntryRepository rationaleRepository,
            ResearchRunDisclosureRepository disclosureRepository,
            ResearchRunDisclosureEntryRepository disclosureEntryRepository,
            ResearchRunService service) {
        this.service = service;
        this.artifactRepository = artifactRepository;
        this.decisionLogRepository = decisionLogRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.rationaleRepository = rationaleRepository;
        this.disclosureRepository = disclosureRepository;
        this.disclosureEntryRepository = disclosureEntryRepository;
    }

    // ------------------------------------------------------------------
    // Decision surfaces: review comments / rationale / disclosure
    // ------------------------------------------------------------------

    /** GC-RSCH-F034 / ADR-067 — attach a bounded review comment to a run surface. */
    ResearchRunReviewComment addReviewComment(UUID projectId, UUID runId, AddReviewCommentCommand command) {
        var run = service.requireRun(projectId, runId);
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
    ResearchRunReviewComment resolveReviewComment(
            UUID projectId, UUID runId, UUID commentId, ResolveReviewCommentCommand command) {
        service.requireRun(projectId, runId);
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
    ResearchRunRationaleEntry addRationaleEntry(UUID projectId, UUID runId, AddRationaleEntryCommand command) {
        var run = service.requireRun(projectId, runId);
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
    ResearchRunDisclosure createDisclosure(UUID projectId, UUID runId, CreateDisclosureCommand command) {
        var run = service.requireRun(projectId, runId);
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
    ResearchRunDisclosureEntry addDisclosureEntry(
            UUID projectId, UUID runId, UUID disclosureId, AddDisclosureEntryCommand command) {
        service.requireRun(projectId, runId);
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
        requireUnder(command.sectionKey(), SECTION_KEY_MAX, SECTION_KEY_JSON_FIELD);
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
}
