package com.keplerops.groundcontrol.domain.research.service;

import static com.keplerops.groundcontrol.domain.research.service.ResearchRunServiceSupport.computeReadiness;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosure;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosureEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGate;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunRationaleEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanCoverageRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanSectionRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateDecisionLogRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRationaleEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunReviewCommentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Project-scoped reads over a research run and its child collections.
 *
 * Split out of {@link ResearchRunService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class ResearchRunReadOperations {

    private final ResearchRunRepository runRepository;
    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunGateRepository gateRepository;
    private final ResearchRunGateDecisionLogRepository decisionLogRepository;
    private final ResearchRunReviewCommentRepository reviewCommentRepository;
    private final ResearchRunRationaleEntryRepository rationaleRepository;
    private final ResearchRunDisclosureRepository disclosureRepository;
    private final ResearchRunDisclosureEntryRepository disclosureEntryRepository;
    private final ProtocolPlanRepository protocolPlanRepository;
    private final ProtocolPlanCoverageRepository protocolPlanCoverageRepository;
    private final ProtocolPlanSectionRepository protocolPlanSectionRepository;
    private final ResearchRunService service;

    @SuppressWarnings("java:S107") // aggregates the run repositories from one place on purpose
    ResearchRunReadOperations(
            ResearchRunRepository runRepository,
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunGateRepository gateRepository,
            ResearchRunGateDecisionLogRepository decisionLogRepository,
            ResearchRunReviewCommentRepository reviewCommentRepository,
            ResearchRunRationaleEntryRepository rationaleRepository,
            ResearchRunDisclosureRepository disclosureRepository,
            ResearchRunDisclosureEntryRepository disclosureEntryRepository,
            ProtocolPlanRepository protocolPlanRepository,
            ProtocolPlanCoverageRepository protocolPlanCoverageRepository,
            ProtocolPlanSectionRepository protocolPlanSectionRepository,
            ResearchRunService service) {
        this.service = service;
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.gateRepository = gateRepository;
        this.decisionLogRepository = decisionLogRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.rationaleRepository = rationaleRepository;
        this.disclosureRepository = disclosureRepository;
        this.disclosureEntryRepository = disclosureEntryRepository;
        this.protocolPlanRepository = protocolPlanRepository;
        this.protocolPlanCoverageRepository = protocolPlanCoverageRepository;
        this.protocolPlanSectionRepository = protocolPlanSectionRepository;
    }

    /**
     * GC-RSCH-F008 — read the active protocol plan (the surface source search
     * and later stages consume). Resolves the ACTIVE {@code PROTOCOL_PLAN}
     * artifact, then the plan tied to that attempt, and bundles its coverage
     * and section rows. {@link NotFoundException} when no plan has been
     * recorded.
     */
    ProtocolPlanAggregate getProtocolPlan(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
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

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------
    ResearchRun getById(UUID projectId, UUID runId) {
        return service.requireRun(projectId, runId);
    }

    ResearchRun getByUid(UUID projectId, String uid) {
        return runRepository
                .findByProjectIdAndUid(projectId, uid)
                .orElseThrow(() -> new NotFoundException("Research run not found: " + uid));
    }

    List<ResearchRun> listByProject(UUID projectId) {
        return runRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    List<ResearchRunArtifact> listArtifacts(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
        return artifactRepository.findByResearchRunIdOrderByCreatedAtAsc(runId);
    }

    List<ResearchRunGate> listGates(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
        return gateRepository.findByResearchRunIdOrderByGatePointAsc(runId);
    }

    List<ResearchRunGateDecisionLog> listGateDecisionLog(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
        return decisionLogRepository.findByResearchRunIdOrderByDecidedAtAsc(runId);
    }

    List<ResearchRunReviewComment> listReviewComments(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
        return reviewCommentRepository.findByResearchRunIdOrderByCreatedAtAsc(runId);
    }

    List<ResearchRunRationaleEntry> listRationale(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
        return rationaleRepository.findByResearchRunIdOrderByRecordedAtAsc(runId);
    }

    ResearchRunDisclosure getDisclosure(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
        return disclosureRepository
                .findFirstByResearchRunIdAndStatus(runId, DisclosureStatus.CURRENT)
                .orElseThrow(() -> new NotFoundException("No current disclosure for run " + runId));
    }

    List<ResearchRunDisclosureEntry> listDisclosureEntries(UUID projectId, UUID runId, UUID disclosureId) {
        service.requireRun(projectId, runId);
        var disclosure = disclosureRepository
                .findById(disclosureId)
                .filter(d -> d.getResearchRun().getId().equals(runId))
                .orElseThrow(() -> new NotFoundException("Disclosure not found: " + disclosureId));
        return disclosureEntryRepository.findByDisclosureId(disclosure.getId());
    }

    /** GC-RSCH-N011 — assemble the bounded observability snapshot from state. */
    ResearchRunSnapshot getSnapshot(UUID projectId, UUID runId) {
        var run = service.requireRun(projectId, runId);
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
}
