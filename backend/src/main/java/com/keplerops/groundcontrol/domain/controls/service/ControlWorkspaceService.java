package com.keplerops.groundcontrol.domain.controls.service;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.model.Finding;
import com.keplerops.groundcontrol.domain.findings.model.FindingLink;
import com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService.AssetScopedFreshnessSummary;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository;
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
 * Assembles the Control and Assurance Workspace per GC-Q011.
 *
 * <p>This is a <strong>read-only composition</strong> over existing aggregates — no new JPA
 * aggregate, table, or migration is introduced. All collaborator collections are loaded once per
 * project and grouped in memory (no N+1): controls, scoped implementations, control tests,
 * effectiveness assessments, risk-control mappings (mapping count only — the observation/evidence
 * collections are not initialised here), findings + finding links (for control exceptions), and
 * scoped assets. Evidence freshness is delegated to {@link EvidenceFreshnessAnalysisService}, computed
 * once per unique linked asset id.
 *
 * <p>Gap/attention semantics and excluded prose payloads are documented on {@link ControlWorkspaceResult}.
 */
@Service
@Transactional(readOnly = true)
public class ControlWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(ControlWorkspaceService.class);

    private static final String UNASSIGNED = "Unassigned";
    private static final String NO_OBSERVATIONS = "NO_OBSERVATIONS";
    private static final Set<String> STALE_STATES = Set.of("STALE", "EXPIRED");

    private final ControlRepository controlRepository;
    private final ScopedControlImplementationRepository scopedControlImplementationRepository;
    private final ControlTestRepository controlTestRepository;
    private final ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository;
    private final RiskControlMappingRepository riskControlMappingRepository;
    private final FindingRepository findingRepository;
    private final FindingLinkRepository findingLinkRepository;
    private final OperationalAssetRepository operationalAssetRepository;
    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    public ControlWorkspaceService(
            ControlRepository controlRepository,
            ScopedControlImplementationRepository scopedControlImplementationRepository,
            ControlTestRepository controlTestRepository,
            ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository,
            RiskControlMappingRepository riskControlMappingRepository,
            FindingRepository findingRepository,
            FindingLinkRepository findingLinkRepository,
            OperationalAssetRepository operationalAssetRepository,
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService) {
        this.controlRepository = controlRepository;
        this.scopedControlImplementationRepository = scopedControlImplementationRepository;
        this.controlTestRepository = controlTestRepository;
        this.controlEffectivenessAssessmentRepository = controlEffectivenessAssessmentRepository;
        this.riskControlMappingRepository = riskControlMappingRepository;
        this.findingRepository = findingRepository;
        this.findingLinkRepository = findingLinkRepository;
        this.operationalAssetRepository = operationalAssetRepository;
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
    }

    /**
     * Assembles the workspace for a project.
     *
     * @param projectId           resolved project UUID (never null)
     * @param asOf                freshness reference instant; null means now
     * @param freshnessWindowDays must be positive
     * @param status              optional control status filter (in-memory)
     * @param controlFunction     optional control function filter (in-memory)
     * @param owner               optional exact owner filter (in-memory; case-sensitive)
     * @param assetId             optional asset-scope filter (validated in-project)
     * @return composed workspace result
     * @throws DomainValidationException if freshnessWindowDays <= 0
     * @throws NotFoundException         if assetId is non-null and not in the project
     */
    public ControlWorkspaceResult workspace(
            UUID projectId,
            Instant asOf,
            int freshnessWindowDays,
            ControlStatus status,
            ControlFunction controlFunction,
            String owner,
            UUID assetId) {

        validateInputs(projectId, freshnessWindowDays, assetId);

        List<Control> controls = controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<ScopedControlImplementation> scopedImpls =
                scopedControlImplementationRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<ControlTest> tests = controlTestRepository.findByProjectIdOrderByTestDateDesc(projectId);
        List<ControlEffectivenessAssessment> assessments =
                controlEffectivenessAssessmentRepository.findByProjectIdOrderByAssessedAtDesc(projectId);
        List<RiskControlMapping> mappings = riskControlMappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<Finding> findings = findingRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<FindingLink> findingLinks = findingLinkRepository.findByProjectId(projectId);
        List<OperationalAsset> rawAssets = operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId);

        Map<UUID, List<ScopedControlImplementation>> scopedByControl = groupScopedByControl(scopedImpls);
        Map<UUID, List<ControlTest>> testsByControl = groupTestsByControl(tests);
        Map<UUID, List<ControlEffectivenessAssessment>> assessmentsByControl = groupAssessmentsByControl(assessments);
        Map<UUID, Integer> directMappingByControl = countMappingsByControl(mappings);
        Map<UUID, Integer> mappingByScopedImpl = countMappingsByScopedImpl(mappings);
        Map<UUID, Finding> findingsById = indexFindings(findings);
        Map<UUID, List<Finding>> exceptionsByControl = groupExceptionsByControl(findingLinks, findingsById);

        // Resolve each control's linked asset ids once (single pass over mappings), then reuse the
        // same map for the freshness dedup and the compose loop.
        Map<UUID, List<UUID>> linkedAssetIdsByControl =
                computeLinkedAssetIdsByControl(controls, scopedByControl, mappings);

        // Compute freshness once per unique linked asset id across all controls.
        Map<UUID, String> freshnessByAsset =
                computeFreshnessByAsset(linkedAssetIdsByControl, projectId, asOf, freshnessWindowDays);

        List<ControlWorkspaceResult.WorkspaceControl> composedControls = new ArrayList<>();
        for (Control control : controls) {
            List<ScopedControlImplementation> controlScoped = scopedByControl.getOrDefault(control.getId(), List.of());
            List<UUID> linkedAssetIds = linkedAssetIdsByControl.getOrDefault(control.getId(), List.of());
            if (!matchesFilters(control, linkedAssetIds, status, controlFunction, owner, assetId)) {
                continue;
            }
            composedControls.add(composeControl(
                    control,
                    controlScoped,
                    testsByControl.getOrDefault(control.getId(), List.of()),
                    assessmentsByControl.getOrDefault(control.getId(), List.of()),
                    directMappingByControl,
                    mappingByScopedImpl,
                    exceptionsByControl.getOrDefault(control.getId(), List.of()),
                    linkedAssetIds,
                    freshnessByAsset));
        }

        List<ControlWorkspaceResult.OwnerQueue> ownerQueues = buildOwnerQueues(composedControls);
        List<ControlWorkspaceResult.WorkspaceAsset> workspaceAssets = loadAssets(rawAssets);

        log.info(
                "control_workspace assembled: project={} controls={} owners={} assets={}",
                projectId,
                composedControls.size(),
                ownerQueues.size(),
                workspaceAssets.size());

        return new ControlWorkspaceResult(composedControls, ownerQueues, workspaceAssets);
    }

    private void validateInputs(UUID projectId, int freshnessWindowDays, UUID assetId) {
        if (freshnessWindowDays <= 0) {
            throw new DomainValidationException(
                    "freshnessWindowDays must be positive",
                    "validation_error",
                    Map.of("parameter", "freshnessWindowDays", "value", freshnessWindowDays));
        }
        if (assetId != null
                && operationalAssetRepository
                        .findByIdAndProjectId(assetId, projectId)
                        .isEmpty()) {
            throw new NotFoundException("Asset not found in project: " + assetId);
        }
    }

    // ── Grouping ───────────────────────────────────────────────────────────────

    private static Map<UUID, List<ScopedControlImplementation>> groupScopedByControl(
            List<ScopedControlImplementation> scopedImpls) {
        Map<UUID, List<ScopedControlImplementation>> map = new LinkedHashMap<>();
        for (ScopedControlImplementation impl : scopedImpls) {
            if (impl.getControl() != null) {
                map.computeIfAbsent(impl.getControl().getId(), k -> new ArrayList<>())
                        .add(impl);
            }
        }
        return map;
    }

    private static Map<UUID, List<ControlTest>> groupTestsByControl(List<ControlTest> tests) {
        Map<UUID, List<ControlTest>> map = new LinkedHashMap<>();
        for (ControlTest test : tests) {
            if (test.getControl() != null) {
                map.computeIfAbsent(test.getControl().getId(), k -> new ArrayList<>())
                        .add(test);
            }
        }
        return map;
    }

    private static Map<UUID, List<ControlEffectivenessAssessment>> groupAssessmentsByControl(
            List<ControlEffectivenessAssessment> assessments) {
        Map<UUID, List<ControlEffectivenessAssessment>> map = new LinkedHashMap<>();
        for (ControlEffectivenessAssessment a : assessments) {
            if (a.getControl() != null) {
                map.computeIfAbsent(a.getControl().getId(), k -> new ArrayList<>())
                        .add(a);
            }
        }
        return map;
    }

    private static Map<UUID, Integer> countMappingsByControl(List<RiskControlMapping> mappings) {
        Map<UUID, Integer> map = new HashMap<>();
        for (RiskControlMapping m : mappings) {
            if (m.getControl() != null) {
                map.merge(m.getControl().getId(), 1, Integer::sum);
            }
        }
        return map;
    }

    private static Map<UUID, Integer> countMappingsByScopedImpl(List<RiskControlMapping> mappings) {
        Map<UUID, Integer> map = new HashMap<>();
        for (RiskControlMapping m : mappings) {
            if (m.getScopedImplementation() != null) {
                map.merge(m.getScopedImplementation().getId(), 1, Integer::sum);
            }
        }
        return map;
    }

    private static Map<UUID, Finding> indexFindings(List<Finding> findings) {
        Map<UUID, Finding> map = new HashMap<>();
        for (Finding f : findings) {
            map.put(f.getId(), f);
        }
        return map;
    }

    /**
     * Groups findings that link to a control (FindingLink targetType CONTROL). {@code link.getFinding().getId()}
     * reads the lazy proxy id without initialising the finding; the full record is resolved from {@code findingsById}.
     *
     * <p>Project scoping is enforced on the consumption side, not here: only links and findings already
     * loaded for the requested project enter this map, and {@link #composeControl} reads the exceptions
     * only for the project's own controls ({@code controlRepository.findByProjectIdOrderByCreatedAtDesc}).
     * A link whose {@code targetEntityId} is some other project's control id therefore never surfaces.
     */
    private static Map<UUID, List<Finding>> groupExceptionsByControl(
            List<FindingLink> findingLinks, Map<UUID, Finding> findingsById) {
        Map<UUID, List<Finding>> map = new LinkedHashMap<>();
        for (FindingLink link : findingLinks) {
            if (link.getTargetType() != FindingLinkTargetType.CONTROL || link.getTargetEntityId() == null) {
                continue;
            }
            Finding finding = findingsById.get(link.getFinding().getId());
            if (finding != null) {
                map.computeIfAbsent(link.getTargetEntityId(), k -> new ArrayList<>())
                        .add(finding);
            }
        }
        return map;
    }

    // ── Asset linkage + freshness ───────────────────────────────────────────────

    /**
     * Resolves each control's linked asset ids in a single pass: scoped-implementation assets plus
     * risk-control mapping assets (mappings attributed to a control directly, or via the control id of
     * the scoped implementation they reference). O(controls + scopedImpls + mappings).
     */
    private static Map<UUID, List<UUID>> computeLinkedAssetIdsByControl(
            List<Control> controls,
            Map<UUID, List<ScopedControlImplementation>> scopedByControl,
            List<RiskControlMapping> mappings) {
        Map<UUID, UUID> controlByScopedImpl = new HashMap<>();
        for (Map.Entry<UUID, List<ScopedControlImplementation>> entry : scopedByControl.entrySet()) {
            for (ScopedControlImplementation impl : entry.getValue()) {
                controlByScopedImpl.put(impl.getId(), entry.getKey());
            }
        }

        Map<UUID, Set<UUID>> assetIdsByControl = new LinkedHashMap<>();
        for (Control control : controls) {
            Set<UUID> ids = new LinkedHashSet<>();
            for (ScopedControlImplementation impl : scopedByControl.getOrDefault(control.getId(), List.of())) {
                if (impl.getOperationalAsset() != null) {
                    ids.add(impl.getOperationalAsset().getId());
                }
            }
            assetIdsByControl.put(control.getId(), ids);
        }

        for (RiskControlMapping m : mappings) {
            if (m.getOperationalAsset() == null) {
                continue;
            }
            UUID controlId = m.getControl() != null
                    ? m.getControl().getId()
                    : (m.getScopedImplementation() != null
                            ? controlByScopedImpl.get(
                                    m.getScopedImplementation().getId())
                            : null);
            Set<UUID> ids = controlId != null ? assetIdsByControl.get(controlId) : null;
            if (ids != null) {
                ids.add(m.getOperationalAsset().getId());
            }
        }

        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, Set<UUID>> entry : assetIdsByControl.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    private Map<UUID, String> computeFreshnessByAsset(
            Map<UUID, List<UUID>> linkedAssetIdsByControl, UUID projectId, Instant asOf, int freshnessWindowDays) {
        Set<UUID> assetIds = new LinkedHashSet<>();
        for (List<UUID> ids : linkedAssetIdsByControl.values()) {
            assetIds.addAll(ids);
        }
        Map<UUID, String> result = new HashMap<>();
        for (UUID assetId : assetIds) {
            AssetScopedFreshnessSummary summary = evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                    projectId, asOf, freshnessWindowDays, assetId);
            result.put(assetId, summary.dominantState());
        }
        return result;
    }

    /** Worst evidence-freshness state over a control's linked assets. EXPIRED > STALE > SUPERSEDED > FRESH > NO_OBSERVATIONS. */
    private static String dominantFreshness(List<UUID> linkedAssetIds, Map<UUID, String> freshnessByAsset) {
        String worst = NO_OBSERVATIONS;
        int worstRank = freshnessRank(NO_OBSERVATIONS);
        for (UUID assetId : linkedAssetIds) {
            String state = freshnessByAsset.getOrDefault(assetId, NO_OBSERVATIONS);
            int rank = freshnessRank(state);
            if (rank > worstRank) {
                worst = state;
                worstRank = rank;
            }
        }
        return worst;
    }

    private static int freshnessRank(String state) {
        return switch (state) {
            case "EXPIRED" -> 4;
            case "STALE" -> 3;
            case "SUPERSEDED" -> 2;
            case "FRESH" -> 1;
            default -> 0; // NO_OBSERVATIONS or unknown
        };
    }

    // ── Composition ──────────────────────────────────────────────────────────────

    private static boolean matchesFilters(
            Control control,
            List<UUID> linkedAssetIds,
            ControlStatus status,
            ControlFunction controlFunction,
            String owner,
            UUID assetId) {
        if (status != null && control.getStatus() != status) {
            return false;
        }
        if (controlFunction != null && control.getControlFunction() != controlFunction) {
            return false;
        }
        if (owner != null && !owner.equals(control.getOwner())) {
            return false;
        }
        return assetId == null || linkedAssetIds.contains(assetId);
    }

    private static ControlWorkspaceResult.WorkspaceControl composeControl(
            Control control,
            List<ScopedControlImplementation> controlScoped,
            List<ControlTest> controlTests,
            List<ControlEffectivenessAssessment> controlAssessments,
            Map<UUID, Integer> directMappingByControl,
            Map<UUID, Integer> mappingByScopedImpl,
            List<Finding> exceptions,
            List<UUID> linkedAssetIds,
            Map<UUID, String> freshnessByAsset) {

        List<ControlWorkspaceResult.WorkspaceScopedImplementation> scoped = new ArrayList<>();
        for (ScopedControlImplementation impl : controlScoped) {
            scoped.add(new ControlWorkspaceResult.WorkspaceScopedImplementation(
                    impl.getId(),
                    impl.getUid(),
                    impl.getName(),
                    impl.getOperationalAsset() != null
                            ? impl.getOperationalAsset().getId()
                            : null));
        }

        List<ControlWorkspaceResult.WorkspaceControlTest> tests = new ArrayList<>();
        for (ControlTest t : controlTests) {
            tests.add(new ControlWorkspaceResult.WorkspaceControlTest(
                    t.getId(),
                    t.getUid(),
                    t.getMethodology(),
                    t.getConclusion(),
                    t.getTestDate(),
                    t.getTesterIdentity()));
        }
        ControlWorkspaceResult.WorkspaceTestSummary testSummary = summarizeTests(controlTests);

        ControlWorkspaceResult.WorkspaceAssessment latestAssessment = null;
        if (!controlAssessments.isEmpty()) {
            ControlEffectivenessAssessment a = controlAssessments.get(0); // ordered assessedAt desc
            latestAssessment = new ControlWorkspaceResult.WorkspaceAssessment(
                    a.getId(),
                    a.getUid(),
                    a.getDesignEffectiveness(),
                    a.getOperatingEffectiveness(),
                    a.getAssessedAt(),
                    a.getAssessor());
        }

        int mappingCount = directMappingByControl.getOrDefault(control.getId(), 0);
        for (ScopedControlImplementation impl : controlScoped) {
            mappingCount += mappingByScopedImpl.getOrDefault(impl.getId(), 0);
        }

        List<ControlWorkspaceResult.WorkspaceExceptionRef> workspaceExceptions = new ArrayList<>();
        for (Finding f : exceptions) {
            workspaceExceptions.add(new ControlWorkspaceResult.WorkspaceExceptionRef(
                    f.getId(), f.getUid(), f.getTitle(), f.getFindingType(), f.getSeverity(), f.getStatus()));
        }

        String staleIndicator = dominantFreshness(linkedAssetIds, freshnessByAsset);
        boolean needsAttention =
                computeNeedsAttention(control.getStatus(), latestAssessment, testSummary, staleIndicator, exceptions);

        return new ControlWorkspaceResult.WorkspaceControl(
                control.getId(),
                control.getUid(),
                control.getTitle(),
                control.getControlFunction(),
                control.getStatus(),
                control.getOwner(),
                control.getCategory(),
                scoped,
                tests,
                testSummary,
                latestAssessment,
                mappingCount,
                workspaceExceptions,
                linkedAssetIds,
                staleIndicator,
                needsAttention);
    }

    private static ControlWorkspaceResult.WorkspaceTestSummary summarizeTests(List<ControlTest> controlTests) {
        int effective = 0;
        int ineffective = 0;
        int notTested = 0;
        for (ControlTest t : controlTests) {
            switch (t.getConclusion()) {
                case EFFECTIVE -> effective++;
                case INEFFECTIVE -> ineffective++;
                case NOT_TESTED -> notTested++;
                default -> {
                    // ControlTestConclusion is a closed enum (EFFECTIVE/INEFFECTIVE/NOT_TESTED);
                    // the default branch satisfies MissingSwitchDefaultCheck only.
                }
            }
        }
        // Tests arrive ordered testDate desc, so the first is the latest.
        ControlTest latest = controlTests.isEmpty() ? null : controlTests.get(0);
        return new ControlWorkspaceResult.WorkspaceTestSummary(
                controlTests.size(),
                effective,
                ineffective,
                notTested,
                latest != null ? latest.getTestDate() : null,
                latest != null ? latest.getConclusion() : null);
    }

    private static boolean computeNeedsAttention(
            ControlStatus status,
            ControlWorkspaceResult.WorkspaceAssessment latestAssessment,
            ControlWorkspaceResult.WorkspaceTestSummary testSummary,
            String staleIndicator,
            List<Finding> exceptions) {
        if (status != ControlStatus.OPERATIONAL) {
            return false;
        }
        if (latestAssessment == null) {
            return true;
        }
        if (latestAssessment.designEffectiveness() == ControlEffectivenessRating.INEFFECTIVE
                || latestAssessment.operatingEffectiveness() == ControlEffectivenessRating.INEFFECTIVE) {
            return true;
        }
        if (testSummary.latestConclusion() == ControlTestConclusion.INEFFECTIVE) {
            return true;
        }
        if (STALE_STATES.contains(staleIndicator)) {
            return true;
        }
        for (Finding f : exceptions) {
            if (f.getStatus() != FindingStatus.VERIFIED_CLOSED) {
                return true;
            }
        }
        return false;
    }

    private static List<ControlWorkspaceResult.OwnerQueue> buildOwnerQueues(
            List<ControlWorkspaceResult.WorkspaceControl> controls) {
        Map<String, List<ControlWorkspaceResult.WorkspaceControl>> byOwner = new LinkedHashMap<>();
        for (ControlWorkspaceResult.WorkspaceControl c : controls) {
            String owner = c.owner() == null || c.owner().isBlank() ? UNASSIGNED : c.owner();
            byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(c);
        }
        List<ControlWorkspaceResult.OwnerQueue> queues = new ArrayList<>();
        for (Map.Entry<String, List<ControlWorkspaceResult.WorkspaceControl>> entry : byOwner.entrySet()) {
            List<String> attentionUids = new ArrayList<>();
            for (ControlWorkspaceResult.WorkspaceControl c : entry.getValue()) {
                if (c.needsAttention()) {
                    attentionUids.add(c.uid());
                }
            }
            queues.add(new ControlWorkspaceResult.OwnerQueue(
                    entry.getKey(), entry.getValue().size(), attentionUids.size(), attentionUids));
        }
        return queues;
    }

    private static List<ControlWorkspaceResult.WorkspaceAsset> loadAssets(List<OperationalAsset> rawAssets) {
        List<ControlWorkspaceResult.WorkspaceAsset> result = new ArrayList<>(rawAssets.size());
        for (OperationalAsset a : rawAssets) {
            result.add(new ControlWorkspaceResult.WorkspaceAsset(
                    a.getId(), a.getUid(), a.getName(), a.getAssetType(), a.getAssetType() == AssetType.BOUNDARY));
        }
        return result;
    }
}
