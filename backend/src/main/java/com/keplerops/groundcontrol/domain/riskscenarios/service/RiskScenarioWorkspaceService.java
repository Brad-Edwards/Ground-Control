package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService.AssetScopedFreshnessSummary;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenarioLink;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the Risk Scenario Workspace per GC-Q009.
 *
 * <p>This is a <strong>read-only composition</strong> over existing aggregates — no new JPA
 * aggregate, table, migration, or AGE path is introduced. The workspace sources:
 * <ul>
 *   <li>Risk scenarios from {@code RiskScenarioRepository.findByProjectIdOrderByCreatedAtDesc}
 *       (or {@code findByIdInAndProjectId} when a compare subset is specified).</li>
 *   <li>Links grouped by scenario id from {@code RiskScenarioLinkRepository.findByProjectId},
 *       fanned into named buckets by {@code RiskScenarioLinkTargetType}.</li>
 *   <li>Assessments from {@code RiskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc}.</li>
 *   <li>Treatment plans from {@code TreatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc}.</li>
 *   <li>Register memberships from {@code RiskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc}.</li>
 *   <li>Scoped assets from {@code OperationalAssetRepository.findByProjectIdAndArchivedAtIsNull}.</li>
 * </ul>
 *
 * <p><strong>Review-indicator interpretation (explicit signals only — preflight rule).</strong>
 * Severity order: REASSESSMENT_REQUIRED > REVIEW_DUE > EVIDENCE_STALE > CURRENT > NO_SIGNAL.
 * <ul>
 *   <li>REASSESSMENT_REQUIRED — any linked assessment has {@code reassessmentRequiredAt != null}.</li>
 *   <li>REVIEW_DUE — any linked register record has {@code nextReviewAt} before {@code asOf}
 *       (null asOf = Instant.now()).</li>
 *   <li>EVIDENCE_STALE — worst evidence freshness dominant state ∈ {STALE, EXPIRED} over linked
 *       asset ids via {@link EvidenceFreshnessAnalysisService#assetScopedEvidenceFreshness}.</li>
 *   <li>CURRENT — has assessments or register records, none triggering a signal.</li>
 *   <li>NO_SIGNAL — no assessments and no register memberships.</li>
 * </ul>
 * {@code updatedAt}, Envers revision history, and other lifecycle fields are never used.
 *
 * <p>Assessment payloads ({@code inputFactors}, {@code computedOutputs}, {@code uncertaintyMetadata},
 * {@code notes}) are never projected into the result or logged (preflight error-leakage rule).
 */
@Service
@Transactional(readOnly = true)
public class RiskScenarioWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(RiskScenarioWorkspaceService.class);

    private static final int MAX_COMPARE = 10;

    /** Review-indicator severity constants. */
    private static final String NO_SIGNAL = "NO_SIGNAL";

    private static final String CURRENT = "CURRENT";
    private static final String EVIDENCE_STALE = "EVIDENCE_STALE";
    private static final String REVIEW_DUE = "REVIEW_DUE";
    private static final String REASSESSMENT_REQUIRED = "REASSESSMENT_REQUIRED";

    private static final Set<String> STALE_STATES = Set.of("STALE", "EXPIRED");

    private final RiskScenarioRepository riskScenarioRepository;
    private final RiskScenarioLinkRepository riskScenarioLinkRepository;
    private final RiskAssessmentResultRepository riskAssessmentResultRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final RiskRegisterRecordRepository riskRegisterRecordRepository;
    private final OperationalAssetRepository operationalAssetRepository;
    private final MethodologyProfileRepository methodologyProfileRepository;
    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    public RiskScenarioWorkspaceService(
            RiskScenarioRepository riskScenarioRepository,
            RiskScenarioLinkRepository riskScenarioLinkRepository,
            RiskAssessmentResultRepository riskAssessmentResultRepository,
            TreatmentPlanRepository treatmentPlanRepository,
            RiskRegisterRecordRepository riskRegisterRecordRepository,
            OperationalAssetRepository operationalAssetRepository,
            MethodologyProfileRepository methodologyProfileRepository,
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService) {
        this.riskScenarioRepository = riskScenarioRepository;
        this.riskScenarioLinkRepository = riskScenarioLinkRepository;
        this.riskAssessmentResultRepository = riskAssessmentResultRepository;
        this.treatmentPlanRepository = treatmentPlanRepository;
        this.riskRegisterRecordRepository = riskRegisterRecordRepository;
        this.operationalAssetRepository = operationalAssetRepository;
        this.methodologyProfileRepository = methodologyProfileRepository;
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
    }

    /**
     * Assembles the workspace for a project.
     *
     * @param projectId              resolved project UUID (never null)
     * @param asOf                   freshness reference instant; null means now
     * @param freshnessWindowDays    must be positive
     * @param assetId                optional asset-scope filter (validated in-project)
     * @param status                 optional scenario status filter (in-memory)
     * @param methodologyProfileId   optional methodology profile filter (validated in-project)
     * @param approvalState          optional assessment approval state filter (in-memory)
     * @param treatmentStatus        optional treatment status filter (in-memory)
     * @param compareIds             optional bounded compare subset (≤ 10 ids); empty = all
     * @return composed workspace result
     * @throws DomainValidationException if freshnessWindowDays ≤ 0 or compareIds.size() > 10
     * @throws NotFoundException         if assetId or methodologyProfileId is not null and not in project
     */
    public RiskScenarioWorkspaceResult workspace(
            UUID projectId,
            Instant asOf,
            int freshnessWindowDays,
            UUID assetId,
            RiskScenarioStatus status,
            UUID methodologyProfileId,
            RiskAssessmentApprovalStatus approvalState,
            TreatmentPlanStatus treatmentStatus,
            List<UUID> compareIds) {

        validateInputs(projectId, freshnessWindowDays, assetId, methodologyProfileId, compareIds);

        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();

        // Load scenarios: compare subset takes priority over full list.
        List<RiskScenario> rawScenarios = compareIds.isEmpty()
                ? riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                : riskScenarioRepository.findByIdInAndProjectId(compareIds, projectId);

        List<RiskScenarioLink> allLinks = riskScenarioLinkRepository.findByProjectId(projectId);
        List<RiskAssessmentResult> allAssessments =
                riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId);
        List<TreatmentPlan> allTreatments = treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<RiskRegisterRecord> allRegisterRecords =
                riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId);

        // Build per-scenario lookup maps.
        Map<UUID, List<RiskScenarioLink>> linksByScenario = groupLinksByScenario(allLinks);
        Map<UUID, List<RiskAssessmentResult>> assessmentsByScenario = groupAssessmentsByScenario(allAssessments);
        Map<UUID, List<TreatmentPlan>> treatmentsByScenario = groupTreatmentsByScenario(allTreatments);
        Map<UUID, List<RiskRegisterRecord>> registersByScenario = groupRegistersByScenario(allRegisterRecords);

        // Compute freshness once per unique ASSET-linked entity id across all links.
        Map<UUID, String> freshnessStateByAsset =
                computeFreshnessByAsset(allLinks, projectId, asOf, freshnessWindowDays);

        // Compose scenarios.
        List<RiskScenarioWorkspaceResult.WorkspaceScenario> scenarios = composeScenarios(
                rawScenarios,
                linksByScenario,
                assessmentsByScenario,
                treatmentsByScenario,
                registersByScenario,
                freshnessStateByAsset,
                effectiveAsOf,
                assetId,
                status,
                approvalState,
                treatmentStatus);

        // Load assets.
        List<RiskScenarioWorkspaceResult.WorkspaceAsset> workspaceAssets = loadAssets(projectId);

        log.info(
                "risk_scenario_workspace assembled: project={} scenarios={} assets={}",
                projectId,
                scenarios.size(),
                workspaceAssets.size());

        return new RiskScenarioWorkspaceResult(scenarios, workspaceAssets);
    }

    private void validateInputs(
            UUID projectId, int freshnessWindowDays, UUID assetId, UUID methodologyProfileId, List<UUID> compareIds) {
        if (freshnessWindowDays <= 0) {
            throw new DomainValidationException(
                    "freshnessWindowDays must be positive",
                    "validation_error",
                    Map.of("parameter", "freshnessWindowDays", "value", freshnessWindowDays));
        }
        if (compareIds != null && compareIds.size() > MAX_COMPARE) {
            throw new DomainValidationException(
                    "compare set must not exceed " + MAX_COMPARE + " scenarios",
                    "validation_error",
                    Map.of("parameter", "compare", "size", compareIds.size()));
        }
        if (assetId != null
                && operationalAssetRepository
                        .findByIdAndProjectId(assetId, projectId)
                        .isEmpty()) {
            throw new NotFoundException("Asset not found in project: " + assetId);
        }
        if (methodologyProfileId != null
                && methodologyProfileRepository
                        .findByIdAndProjectId(methodologyProfileId, projectId)
                        .isEmpty()) {
            throw new NotFoundException("MethodologyProfile not found in project: " + methodologyProfileId);
        }
    }

    private List<RiskScenarioWorkspaceResult.WorkspaceAsset> loadAssets(UUID projectId) {
        List<OperationalAsset> rawAssets = operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId);
        List<RiskScenarioWorkspaceResult.WorkspaceAsset> result = new ArrayList<>(rawAssets.size());
        for (OperationalAsset a : rawAssets) {
            result.add(new RiskScenarioWorkspaceResult.WorkspaceAsset(
                    a.getId(), a.getUid(), a.getName(), a.getAssetType(), a.getAssetType() == AssetType.BOUNDARY));
        }
        return result;
    }

    private static Map<UUID, List<RiskScenarioLink>> groupLinksByScenario(List<RiskScenarioLink> allLinks) {
        Map<UUID, List<RiskScenarioLink>> map = new LinkedHashMap<>();
        for (RiskScenarioLink link : allLinks) {
            map.computeIfAbsent(link.getRiskScenario().getId(), k -> new ArrayList<>())
                    .add(link);
        }
        return map;
    }

    private static Map<UUID, List<RiskAssessmentResult>> groupAssessmentsByScenario(
            List<RiskAssessmentResult> allAssessments) {
        Map<UUID, List<RiskAssessmentResult>> map = new LinkedHashMap<>();
        for (RiskAssessmentResult a : allAssessments) {
            map.computeIfAbsent(a.getRiskScenario().getId(), k -> new ArrayList<>())
                    .add(a);
        }
        return map;
    }

    private static Map<UUID, List<TreatmentPlan>> groupTreatmentsByScenario(List<TreatmentPlan> allTreatments) {
        Map<UUID, List<TreatmentPlan>> map = new LinkedHashMap<>();
        for (TreatmentPlan t : allTreatments) {
            if (t.getRiskScenario() != null) {
                map.computeIfAbsent(t.getRiskScenario().getId(), k -> new ArrayList<>())
                        .add(t);
            }
        }
        return map;
    }

    /** Maps scenario id → register records that include that scenario. */
    private static Map<UUID, List<RiskRegisterRecord>> groupRegistersByScenario(List<RiskRegisterRecord> allRecords) {
        Map<UUID, List<RiskRegisterRecord>> map = new LinkedHashMap<>();
        for (RiskRegisterRecord rrr : allRecords) {
            for (RiskScenario rs : rrr.getRiskScenarios()) {
                map.computeIfAbsent(rs.getId(), k -> new ArrayList<>()).add(rrr);
            }
        }
        return map;
    }

    /** Computes evidence freshness once per unique ASSET-linked entity id (dedup). */
    private Map<UUID, String> computeFreshnessByAsset(
            List<RiskScenarioLink> allLinks, UUID projectId, Instant asOf, int freshnessWindowDays) {
        Set<UUID> linkedAssetIds = new LinkedHashSet<>();
        for (RiskScenarioLink link : allLinks) {
            if (link.getTargetType() == RiskScenarioLinkTargetType.ASSET && link.getTargetEntityId() != null) {
                linkedAssetIds.add(link.getTargetEntityId());
            }
        }
        Map<UUID, String> result = new HashMap<>();
        for (UUID aid : linkedAssetIds) {
            AssetScopedFreshnessSummary summary = evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                    projectId, asOf, freshnessWindowDays, aid);
            result.put(aid, summary.dominantState());
        }
        return result;
    }

    private static List<RiskScenarioWorkspaceResult.WorkspaceScenario> composeScenarios(
            List<RiskScenario> rawScenarios,
            Map<UUID, List<RiskScenarioLink>> linksByScenario,
            Map<UUID, List<RiskAssessmentResult>> assessmentsByScenario,
            Map<UUID, List<TreatmentPlan>> treatmentsByScenario,
            Map<UUID, List<RiskRegisterRecord>> registersByScenario,
            Map<UUID, String> freshnessStateByAsset,
            Instant effectiveAsOf,
            UUID assetId,
            RiskScenarioStatus status,
            RiskAssessmentApprovalStatus approvalState,
            TreatmentPlanStatus treatmentStatus) {
        List<RiskScenarioWorkspaceResult.WorkspaceScenario> result = new ArrayList<>();
        for (RiskScenario rs : rawScenarios) {
            List<RiskScenarioLink> links = linksByScenario.getOrDefault(rs.getId(), List.of());
            if (!matchesFilters(rs, links, assetId, status)) {
                continue;
            }
            result.add(composeScenario(
                    rs,
                    links,
                    assessmentsByScenario.getOrDefault(rs.getId(), List.of()),
                    treatmentsByScenario.getOrDefault(rs.getId(), List.of()),
                    registersByScenario.getOrDefault(rs.getId(), List.of()),
                    freshnessStateByAsset,
                    effectiveAsOf,
                    approvalState,
                    treatmentStatus));
        }
        return result;
    }

    private static boolean matchesFilters(
            RiskScenario rs, List<RiskScenarioLink> links, UUID assetId, RiskScenarioStatus status) {
        if (status != null && rs.getStatus() != status) {
            return false;
        }
        return assetId == null || hasAssetLink(links, assetId);
    }

    private static boolean hasAssetLink(List<RiskScenarioLink> links, UUID assetId) {
        for (RiskScenarioLink link : links) {
            if (link.getTargetType() == RiskScenarioLinkTargetType.ASSET && assetId.equals(link.getTargetEntityId())) {
                return true;
            }
        }
        return false;
    }

    private static RiskScenarioWorkspaceResult.WorkspaceScenario composeScenario(
            RiskScenario rs,
            List<RiskScenarioLink> links,
            List<RiskAssessmentResult> assessments,
            List<TreatmentPlan> treatments,
            List<RiskRegisterRecord> registerRecords,
            Map<UUID, String> freshnessStateByAsset,
            Instant effectiveAsOf,
            RiskAssessmentApprovalStatus approvalState,
            TreatmentPlanStatus treatmentStatus) {

        List<UUID> linkedAssetIds = new ArrayList<>();
        List<RiskScenarioWorkspaceResult.WorkspaceLink> controls = new ArrayList<>();
        List<RiskScenarioWorkspaceResult.WorkspaceLink> findings = new ArrayList<>();
        List<RiskScenarioWorkspaceResult.WorkspaceLink> evidence = new ArrayList<>();
        List<RiskScenarioWorkspaceResult.WorkspaceLink> requirements = new ArrayList<>();

        for (RiskScenarioLink link : links) {
            switch (link.getTargetType()) {
                case ASSET -> addIfPresent(linkedAssetIds, link.getTargetEntityId());
                case CONTROL -> controls.add(toWorkspaceLink(link));
                case FINDING -> findings.add(toWorkspaceLink(link));
                case EVIDENCE -> evidence.add(toWorkspaceLink(link));
                case REQUIREMENT -> requirements.add(toWorkspaceLink(link));
                default -> {
                    // THREAT_MODEL, VULNERABILITY, AUDIT_RECORD, RISK_REGISTER_RECORD,
                    // RISK_ASSESSMENT_RESULT, TREATMENT_PLAN, METHODOLOGY_PROFILE,
                    // OBSERVATION, EXTERNAL — not surfaced in the workspace result.
                }
            }
        }

        List<RiskScenarioWorkspaceResult.WorkspaceAssessment> workspaceAssessments =
                composeAssessments(assessments, approvalState);
        List<RiskScenarioWorkspaceResult.WorkspaceTreatment> workspaceTreatments =
                composeTreatments(treatments, treatmentStatus);
        List<RiskScenarioWorkspaceResult.WorkspaceRegisterRef> workspaceRegisters =
                composeRegisterRefs(registerRecords);

        String reviewIndicator = computeReviewIndicator(
                workspaceAssessments, workspaceRegisters, linkedAssetIds, freshnessStateByAsset, effectiveAsOf);

        return new RiskScenarioWorkspaceResult.WorkspaceScenario(
                rs.getId(),
                rs.getUid(),
                rs.getTitle(),
                rs.getStatus(),
                rs.getThreat(),
                rs.getMethod(),
                rs.getAsset(),
                rs.getEffect(),
                rs.getTimeHorizon(),
                rs.getFairSentence(),
                linkedAssetIds,
                controls,
                findings,
                evidence,
                requirements,
                workspaceAssessments,
                workspaceTreatments,
                workspaceRegisters,
                reviewIndicator);
    }

    private static void addIfPresent(List<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private static RiskScenarioWorkspaceResult.WorkspaceLink toWorkspaceLink(RiskScenarioLink link) {
        return new RiskScenarioWorkspaceResult.WorkspaceLink(
                link.getTargetEntityId(), link.getTargetIdentifier(), link.getTargetTitle(), link.getTargetUrl());
    }

    private static List<RiskScenarioWorkspaceResult.WorkspaceAssessment> composeAssessments(
            List<RiskAssessmentResult> assessments, RiskAssessmentApprovalStatus approvalState) {
        List<RiskScenarioWorkspaceResult.WorkspaceAssessment> result = new ArrayList<>();
        for (RiskAssessmentResult a : assessments) {
            if (approvalState != null && a.getApprovalState() != approvalState) {
                continue;
            }
            MethodologyProfile mp = a.getMethodologyProfile();
            result.add(new RiskScenarioWorkspaceResult.WorkspaceAssessment(
                    a.getId(),
                    mp != null ? mp.getName() : null,
                    a.getApprovalState(),
                    a.getAssessmentAt(),
                    a.getConfidence(),
                    a.getReassessmentRequiredAt(),
                    a.getComputedOutputs() != null && !a.getComputedOutputs().isEmpty()));
        }
        return result;
    }

    private static List<RiskScenarioWorkspaceResult.WorkspaceTreatment> composeTreatments(
            List<TreatmentPlan> treatments, TreatmentPlanStatus treatmentStatus) {
        List<RiskScenarioWorkspaceResult.WorkspaceTreatment> result = new ArrayList<>();
        for (TreatmentPlan t : treatments) {
            if (treatmentStatus != null && t.getStatus() != treatmentStatus) {
                continue;
            }
            result.add(new RiskScenarioWorkspaceResult.WorkspaceTreatment(
                    t.getId(), t.getUid(), t.getTitle(), t.getStrategy(), t.getStatus(), t.getOwner(), t.getDueDate()));
        }
        return result;
    }

    private static List<RiskScenarioWorkspaceResult.WorkspaceRegisterRef> composeRegisterRefs(
            List<RiskRegisterRecord> records) {
        List<RiskScenarioWorkspaceResult.WorkspaceRegisterRef> result = new ArrayList<>();
        for (RiskRegisterRecord r : records) {
            result.add(new RiskScenarioWorkspaceResult.WorkspaceRegisterRef(
                    r.getId(), r.getUid(), r.getTitle(), r.getStatus(), r.getNextReviewAt()));
        }
        return result;
    }

    /**
     * Computes the review indicator using explicit signals only (preflight rule — never updatedAt/Envers).
     *
     * <p>Severity: REASSESSMENT_REQUIRED > REVIEW_DUE > EVIDENCE_STALE > CURRENT > NO_SIGNAL.
     */
    private static String computeReviewIndicator(
            List<RiskScenarioWorkspaceResult.WorkspaceAssessment> assessments,
            List<RiskScenarioWorkspaceResult.WorkspaceRegisterRef> registerRecords,
            List<UUID> linkedAssetIds,
            Map<UUID, String> freshnessStateByAsset,
            Instant effectiveAsOf) {

        boolean hasSignals = !assessments.isEmpty() || !registerRecords.isEmpty();

        // REASSESSMENT_REQUIRED — highest explicit signal.
        for (RiskScenarioWorkspaceResult.WorkspaceAssessment a : assessments) {
            if (a.reassessmentRequiredAt() != null) {
                return REASSESSMENT_REQUIRED;
            }
        }

        // REVIEW_DUE — explicit register nextReviewAt signal.
        for (RiskScenarioWorkspaceResult.WorkspaceRegisterRef r : registerRecords) {
            if (r.nextReviewAt() != null && r.nextReviewAt().isBefore(effectiveAsOf)) {
                return REVIEW_DUE;
            }
        }

        // EVIDENCE_STALE — worst evidence freshness over linked asset ids.
        for (UUID aid : linkedAssetIds) {
            String state = freshnessStateByAsset.getOrDefault(aid, "");
            if (STALE_STATES.contains(state)) {
                return EVIDENCE_STALE;
            }
        }

        // CURRENT or NO_SIGNAL.
        return hasSignals ? CURRENT : NO_SIGNAL;
    }
}
