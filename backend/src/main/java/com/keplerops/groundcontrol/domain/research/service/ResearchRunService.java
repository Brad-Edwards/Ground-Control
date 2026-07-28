package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
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
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
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
import java.util.List;
import java.util.Map;
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

    static final Logger log = LoggerFactory.getLogger(ResearchRunService.class);

    static final int UID_MAX = 50;
    static final int LOCATOR_MAX = 500;
    static final int HASH_MAX = 128;
    static final int IDEMPOTENCY_KEY_MAX = 200;
    static final int OPTION_ID_MAX = 200;
    static final int RATIONALE_MAX = 1000;
    static final int ERROR_CODE_MAX = 100;
    static final int ERROR_CLASS_MAX = 40;
    static final int ERROR_SUMMARY_MAX = 500;
    static final int SUBJECT_KEY_MAX = 200;
    static final int SUMMARY_MAX = 2000;
    static final int BODY_MAX = 2000;
    static final int CONFIDENCE_MAX = 500;
    static final int SECTION_KEY_MAX = 200;
    static final int MODEL_LABEL_MAX = 200;
    static final int QUESTION_KEY_MAX = 200;
    static final int ACTION_ID_MAX = 200;
    static final int RECOMMENDATION_SUMMARY_MAX = 1000;
    static final int METHOD_KEY_MAX = 200;
    static final int SOURCE_REF_MAX = 500;
    static final int SOURCE_LABEL_MAX = 500;
    static final int ENTRY_KEY_MAX = 200;
    static final int STATEMENT_MAX = 2000;
    static final int PROFILE_VERSION_MAX = 100;
    static final String CONTRACT_SCHEMA_VERSION = "1";
    static final String CONTRACT_ENTRY_KEY_FIELD = "entryKey";
    static final String LOCATOR_FIELD = "locator";
    static final String METHOD_KEY_FIELD = "methodKey";
    static final String REFERENCES_ENTRY_KEY_FIELD = "references_entry_key";
    static final String SOURCE_ID_FIELD = "source_id";
    static final int PROTOCOL_SCHEMA_VERSION_MAX = 40;
    static final int ANSWER_SUMMARY_MAX = 2000;
    static final int PROTOCOL_RATIONALE_MAX = 2000;
    static final int DECISION_REFERENCE_MAX = 200;
    static final String CONTRACT_ENTRY_KEY_JSON_FIELD = "contractEntryKey";
    static final String DISPOSITION_FIELD = "disposition";
    static final String SECTION_KEY_JSON_FIELD = "sectionKey";
    static final String SECTION_KIND_FIELD = "sectionKind";

    static final String AUTONOMOUS_DEFAULT_BASIS = "AUTONOMOUS_DEFAULT";

    static final String INVALID_CODE = "research_run_invalid";
    static final String FIELD = "field";
    static final String NO_ACTIVE_METHODOLOGY_SELECTION = "No active methodology selection for run ";
    static final String CURRENT_STAGE = "current_stage";
    static final String GATE_POINT = "gate_point";
    static final String TARGET_STAGE = "targetStage";
    static final String RATIONALE_SUMMARY = "rationaleSummary";
    static final String TARGET_ARTIFACT_ID = "targetArtifactId";
    static final String TARGET_DECISION_LOG_ID = "targetDecisionLogId";

    private final ResearchRunRepository runRepository;
    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunProtocolPlanOperations researchRunProtocolPlanOperations;
    private final ResearchRunContractOperations researchRunContractOperations;
    private final ResearchRunMethodologyOperations researchRunMethodologyOperations;
    private final ResearchRunDecisionSurfaceOperations researchRunDecisionSurfaceOperations;
    private final ResearchRunLifecycleOperations researchRunLifecycleOperations;
    private final ResearchRunGateOperations researchRunGateOperations;
    private final ResearchRunArtifactOperations researchRunArtifactOperations;
    private final ResearchRunStageOperations researchRunStageOperations;
    private final ResearchRunReadOperations researchRunReadOperations;

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

        this.researchRunProtocolPlanOperations = new ResearchRunProtocolPlanOperations(
                artifactRepository,
                methodologySelectionRepository,
                contractRepository,
                contractEntryRepository,
                protocolPlanRepository,
                protocolPlanCoverageRepository,
                protocolPlanSectionRepository,
                this);

        this.researchRunContractOperations = new ResearchRunContractOperations(
                artifactRepository,
                rationaleRepository,
                methodologySelectionRepository,
                methodologySourceRepository,
                methodologyCatalog,
                contractRepository,
                contractEntryRepository,
                contractEntrySourceLinkRepository,
                contractRejectedAlternativeRepository,
                this);

        this.researchRunMethodologyOperations = new ResearchRunMethodologyOperations(
                artifactRepository,
                methodologySelectionRepository,
                methodologySourceRepository,
                methodologyCatalog,
                this);

        this.researchRunDecisionSurfaceOperations = new ResearchRunDecisionSurfaceOperations(
                artifactRepository,
                decisionLogRepository,
                reviewCommentRepository,
                rationaleRepository,
                disclosureRepository,
                disclosureEntryRepository,
                this);

        this.researchRunLifecycleOperations = new ResearchRunLifecycleOperations(
                runRepository,
                artifactRepository,
                decisionLogRepository,
                disclosureRepository,
                disclosureEntryRepository,
                this);

        this.researchRunGateOperations =
                new ResearchRunGateOperations(runRepository, gateRepository, decisionLogRepository, this);

        this.researchRunArtifactOperations = new ResearchRunArtifactOperations(
                runRepository, artifactRepository, gateRepository, disclosureRepository, this);

        this.researchRunStageOperations = new ResearchRunStageOperations(
                runRepository,
                artifactRepository,
                gateRepository,
                decisionLogRepository,
                intakeRepository,
                projectService,
                protocolPlanRepository,
                protocolPlanCoverageRepository,
                this);

        this.researchRunReadOperations = new ResearchRunReadOperations(
                runRepository,
                artifactRepository,
                gateRepository,
                decisionLogRepository,
                reviewCommentRepository,
                rationaleRepository,
                disclosureRepository,
                disclosureEntryRepository,
                protocolPlanRepository,
                protocolPlanCoverageRepository,
                protocolPlanSectionRepository,
                this);
    }

    /**
     * The attempt number of the active artifact produced by the stage this gate
     * guards, or null when none is recorded yet. Tying each decision-log row to a
     * concrete artifact attempt lets two decisions on the same run/gate after a
     * rework be reconciled to the superseded versus current attempt (ADR-066),
     * instead of relying on chronology alone.
     */
    Integer activeAttemptForGate(ResearchRun run, ResearchGatePoint gatePoint) {
        var guardedStage = gatePoint.guardedStageExit();
        return artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        run.getId(), guardedStage.outputArtifactType(), ResearchArtifactStatus.ACTIVE)
                .map(ResearchRunArtifact::getAttemptNo)
                .orElse(null);
    }
    /**
     * Delegates to the methodology collaborator, which owns the selection and
     * source repositories this check reads.
     */
    void requireMethodologySourceCoverageComplete(UUID runId) {
        researchRunMethodologyOperations.requireMethodologySourceCoverageComplete(runId);
    }

    static String key(RecordMethodologyRequirementsContractCommand.EntryCommand e) {
        return e.entryKey().trim();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    ResearchRun requireRun(UUID projectId, UUID runId) {
        return runRepository
                .findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> new NotFoundException("Research run not found: " + runId));
    }

    static void requireActive(ResearchRun run) {
        if (run.getStatus() != ResearchRunStatus.IN_PROGRESS && run.getStatus() != ResearchRunStatus.BLOCKED) {
            throw new ConflictException(
                    "Run is not active (status " + run.getStatus() + ")",
                    "research_run_not_active",
                    Map.of("status", run.getStatus().name()));
        }
    }

    static void requireUnder(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new DomainValidationException(
                    "Field " + field + " exceeds max length", INVALID_CODE, Map.of(FIELD, field, "max", max));
        }
    }

    static String emptyToNull(String value) {
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
    static String currentActor() {
        return emptyToNull(ActorHolder.get());
    }

    public ProtocolPlanAggregate recordProtocolPlan(UUID projectId, UUID runId, RecordProtocolPlanCommand command) {
        return researchRunProtocolPlanOperations.recordProtocolPlan(projectId, runId, command);
    }

    public MethodologyRequirementsContractAggregate recordMethodologyRequirementsContract(
            UUID projectId, UUID runId, RecordMethodologyRequirementsContractCommand command) {
        return researchRunContractOperations.recordMethodologyRequirementsContract(projectId, runId, command);
    }

    @Transactional(readOnly = true)
    public MethodologyRequirementsContractAggregate getMethodologyRequirementsContract(UUID projectId, UUID runId) {
        return researchRunContractOperations.getMethodologyRequirementsContract(projectId, runId);
    }

    public ResearchRunMethodologySelection selectMethodology(UUID projectId, UUID runId, SelectMethodologyCommand cmd) {
        return researchRunMethodologyOperations.selectMethodology(projectId, runId, cmd);
    }

    public ResearchRunMethodologySource recordMethodologySource(
            UUID projectId, UUID runId, RecordMethodologySourceCommand cmd) {
        return researchRunMethodologyOperations.recordMethodologySource(projectId, runId, cmd);
    }

    public ResearchRunMethodologySource updateMethodologySourceState(
            UUID projectId, UUID runId, UUID sourceId, UpdateMethodologySourceStateCommand cmd) {
        return researchRunMethodologyOperations.updateMethodologySourceState(projectId, runId, sourceId, cmd);
    }

    @Transactional(readOnly = true)
    public List<com.keplerops.groundcontrol.domain.research.model.MethodProfile> listMethodologyCatalog() {
        return researchRunMethodologyOperations.listMethodologyCatalog();
    }

    @Transactional(readOnly = true)
    public ResearchRunMethodologySelection getMethodologySelection(UUID projectId, UUID runId) {
        return researchRunMethodologyOperations.getMethodologySelection(projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunMethodologySource> listMethodologySources(UUID projectId, UUID runId) {
        return researchRunMethodologyOperations.listMethodologySources(projectId, runId);
    }

    public ResearchRunReviewComment addReviewComment(UUID projectId, UUID runId, AddReviewCommentCommand command) {
        return researchRunDecisionSurfaceOperations.addReviewComment(projectId, runId, command);
    }

    public ResearchRunReviewComment resolveReviewComment(
            UUID projectId, UUID runId, UUID commentId, ResolveReviewCommentCommand command) {
        return researchRunDecisionSurfaceOperations.resolveReviewComment(projectId, runId, commentId, command);
    }

    public ResearchRunRationaleEntry addRationaleEntry(UUID projectId, UUID runId, AddRationaleEntryCommand command) {
        return researchRunDecisionSurfaceOperations.addRationaleEntry(projectId, runId, command);
    }

    public ResearchRunDisclosure createDisclosure(UUID projectId, UUID runId, CreateDisclosureCommand command) {
        return researchRunDecisionSurfaceOperations.createDisclosure(projectId, runId, command);
    }

    public ResearchRunDisclosureEntry addDisclosureEntry(
            UUID projectId, UUID runId, UUID disclosureId, AddDisclosureEntryCommand command) {
        return researchRunDecisionSurfaceOperations.addDisclosureEntry(projectId, runId, disclosureId, command);
    }

    public ResearchRun stop(UUID projectId, UUID runId) {
        return researchRunLifecycleOperations.stop(projectId, runId);
    }

    public ResearchRun fail(UUID projectId, UUID runId, FailRunCommand command) {
        return researchRunLifecycleOperations.fail(projectId, runId, command);
    }

    public ResearchRun resume(UUID projectId, UUID runId) {
        return researchRunLifecycleOperations.resume(projectId, runId);
    }

    public ResearchRun recordUsage(UUID projectId, UUID runId, long tokens, long costUsdMicros) {
        return researchRunLifecycleOperations.recordUsage(projectId, runId, tokens, costUsdMicros);
    }

    public ResearchRun complete(UUID projectId, UUID runId) {
        return researchRunLifecycleOperations.complete(projectId, runId);
    }

    public ResearchRunGate resolveGate(UUID projectId, UUID runId, GateDecisionCommand command) {
        return researchRunGateOperations.resolveGate(projectId, runId, command);
    }

    public ResearchRunArtifact recordArtifact(UUID projectId, UUID runId, RecordArtifactCommand command) {
        return researchRunArtifactOperations.recordArtifact(projectId, runId, command);
    }

    public ResearchRun start(StartResearchRunCommand command) {
        return researchRunStageOperations.start(command);
    }

    public ResearchRun advanceStage(UUID projectId, UUID runId, AdvanceStageCommand command) {
        return researchRunStageOperations.advanceStage(projectId, runId, command);
    }

    @Transactional(readOnly = true)
    public ProtocolPlanAggregate getProtocolPlan(UUID projectId, UUID runId) {
        return researchRunReadOperations.getProtocolPlan(projectId, runId);
    }

    @Transactional(readOnly = true)
    public ResearchRun getById(UUID projectId, UUID runId) {
        return researchRunReadOperations.getById(projectId, runId);
    }

    @Transactional(readOnly = true)
    public ResearchRun getByUid(UUID projectId, String uid) {
        return researchRunReadOperations.getByUid(projectId, uid);
    }

    @Transactional(readOnly = true)
    public List<ResearchRun> listByProject(UUID projectId) {
        return researchRunReadOperations.listByProject(projectId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunArtifact> listArtifacts(UUID projectId, UUID runId) {
        return researchRunReadOperations.listArtifacts(projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunGate> listGates(UUID projectId, UUID runId) {
        return researchRunReadOperations.listGates(projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunGateDecisionLog> listGateDecisionLog(UUID projectId, UUID runId) {
        return researchRunReadOperations.listGateDecisionLog(projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunReviewComment> listReviewComments(UUID projectId, UUID runId) {
        return researchRunReadOperations.listReviewComments(projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunRationaleEntry> listRationale(UUID projectId, UUID runId) {
        return researchRunReadOperations.listRationale(projectId, runId);
    }

    @Transactional(readOnly = true)
    public ResearchRunDisclosure getDisclosure(UUID projectId, UUID runId) {
        return researchRunReadOperations.getDisclosure(projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<ResearchRunDisclosureEntry> listDisclosureEntries(UUID projectId, UUID runId, UUID disclosureId) {
        return researchRunReadOperations.listDisclosureEntries(projectId, runId, disclosureId);
    }

    @Transactional(readOnly = true)
    public ResearchRunSnapshot getSnapshot(UUID projectId, UUID runId) {
        return researchRunReadOperations.getSnapshot(projectId, runId);
    }
}
