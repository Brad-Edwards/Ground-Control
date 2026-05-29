package com.keplerops.groundcontrol.domain.threatmodels.service;

import com.keplerops.groundcontrol.domain.assets.model.AssetRelation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService.AssetScopedFreshnessSummary;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModelLink;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelLinkRepository;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the Threat Modeling Workspace per GC-Q010.
 *
 * <p>This is a <strong>read-only composition</strong> over existing aggregates — no new JPA
 * aggregate, table, migration, or AGE path is introduced. The workspace sources:
 * <ul>
 *   <li>Scoped operational assets (archived rows excluded) from
 *       {@code OperationalAssetRepository.findByProjectIdAndArchivedAtIsNull}. Boundaries are
 *       partitioned in-memory by {@code AssetType.BOUNDARY}; no separate query is needed.</li>
 *   <li>Active asset relations (flows) from
 *       {@code AssetRelationRepository.findActiveByProjectId}.</li>
 *   <li>Threat model entries from
 *       {@code ThreatModelRepository.findByProjectIdOrderByCreatedAtDesc}.</li>
 *   <li>Links grouped by target type from
 *       {@code ThreatModelLinkRepository.findByProjectId}.</li>
 * </ul>
 *
 * <p><strong>Stale-indicator interpretation.</strong> {@code ThreatModel} has no review-cadence
 * temporal fields. Staleness is therefore derived from the evidence freshness of the assets the
 * threat model is linked to, reusing
 * {@link EvidenceFreshnessAnalysisService#assetScopedEvidenceFreshness} exactly as
 * {@code VendorRiskAggregationService} already does. Per scoped asset we compute freshness once
 * (deduped into a {@code Map<assetId, dominantState>}), then each entry's indicator is the worst
 * dominant state among its linked-asset ids
 * ({@code STALE/EXPIRED > SUPERSEDED > FRESH > NO_OBSERVATIONS}). If product later wants
 * threat-model review-cadence staleness specifically, that requires explicit review fields — flagged
 * here so it can be redirected via issue comment.
 *
 * <p>Optional {@code stride}/{@code status} narrowing is applied in-memory over the already-loaded
 * list (small N); the param seam keeps room for future filters without a new query.
 */
@Service
@Transactional(readOnly = true)
public class ThreatModelWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(ThreatModelWorkspaceService.class);

    private static final String NO_OBSERVATIONS = "NO_OBSERVATIONS";
    private static final String FRESH = "FRESH";
    private static final String STALE = "STALE";
    private static final String EXPIRED = "EXPIRED";
    private static final String SUPERSEDED = "SUPERSEDED";

    /** Severity order for dominant state rollup (higher index = worse). */
    private static final List<String> STATE_SEVERITY = List.of(NO_OBSERVATIONS, FRESH, SUPERSEDED, STALE, EXPIRED);

    private final OperationalAssetRepository operationalAssetRepository;
    private final AssetRelationRepository assetRelationRepository;
    private final ThreatModelRepository threatModelRepository;
    private final ThreatModelLinkRepository threatModelLinkRepository;
    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    public ThreatModelWorkspaceService(
            OperationalAssetRepository operationalAssetRepository,
            AssetRelationRepository assetRelationRepository,
            ThreatModelRepository threatModelRepository,
            ThreatModelLinkRepository threatModelLinkRepository,
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService) {
        this.operationalAssetRepository = operationalAssetRepository;
        this.assetRelationRepository = assetRelationRepository;
        this.threatModelRepository = threatModelRepository;
        this.threatModelLinkRepository = threatModelLinkRepository;
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
    }

    /**
     * Assembles the workspace for a project.
     *
     * @param projectId          resolved project UUID (never null)
     * @param asOf               freshness reference instant; null means now
     * @param freshnessWindowDays must be positive
     * @param assetId            optional asset-scope filter (validated in-project)
     * @param stride             optional STRIDE category filter (in-memory)
     * @param status             optional entry status filter (in-memory)
     * @return composed workspace result
     * @throws DomainValidationException if freshnessWindowDays &le; 0
     * @throws NotFoundException         if assetId is not null and not in the project
     */
    public ThreatModelWorkspaceResult workspace(
            UUID projectId,
            Instant asOf,
            int freshnessWindowDays,
            UUID assetId,
            StrideCategory stride,
            ThreatModelStatus status) {

        if (freshnessWindowDays <= 0) {
            throw new DomainValidationException(
                    "freshnessWindowDays must be positive",
                    "validation_error",
                    Map.of("parameter", "freshnessWindowDays", "value", freshnessWindowDays));
        }

        // Validate assetId is in-project before any asset-scoped lookup (mirrors
        // EvidenceFreshnessAnalysisService.analyze).
        if (assetId != null
                && operationalAssetRepository
                        .findByIdAndProjectId(assetId, projectId)
                        .isEmpty()) {
            throw new NotFoundException("Asset not found in project: " + assetId);
        }

        // 1. Load assets (all non-archived; partition boundaries in-memory)
        List<OperationalAsset> rawAssets = operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId);
        List<ThreatModelWorkspaceResult.WorkspaceAsset> workspaceAssets = new ArrayList<>(rawAssets.size());
        for (OperationalAsset a : rawAssets) {
            workspaceAssets.add(new ThreatModelWorkspaceResult.WorkspaceAsset(
                    a.getId(), a.getUid(), a.getName(), a.getAssetType(), a.getAssetType() == AssetType.BOUNDARY));
        }

        // 2. Load flows
        List<AssetRelation> rawRelations = assetRelationRepository.findActiveByProjectId(projectId);
        List<ThreatModelWorkspaceResult.WorkspaceFlow> flows = new ArrayList<>(rawRelations.size());
        for (AssetRelation r : rawRelations) {
            flows.add(new ThreatModelWorkspaceResult.WorkspaceFlow(
                    r.getId(), r.getSource().getId(), r.getTarget().getId(), r.getRelationType()));
        }

        // 3. Load threat model entries and links
        List<ThreatModel> rawEntries = threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<ThreatModelLink> allLinks = threatModelLinkRepository.findByProjectId(projectId);

        // Group links by threat-model id
        Map<UUID, List<ThreatModelLink>> linksByTm = new LinkedHashMap<>();
        for (ThreatModelLink link : allLinks) {
            linksByTm
                    .computeIfAbsent(link.getThreatModel().getId(), k -> new ArrayList<>())
                    .add(link);
        }

        // Collect unique asset ids referenced via ASSET links across all entries
        Set<UUID> linkedAssetIds = new java.util.LinkedHashSet<>();
        for (ThreatModelLink link : allLinks) {
            if (link.getTargetType() == ThreatModelLinkTargetType.ASSET && link.getTargetEntityId() != null) {
                linkedAssetIds.add(link.getTargetEntityId());
            }
        }

        // Compute freshness once per unique asset id (dedup)
        Map<UUID, String> freshnessStateByAsset = new HashMap<>();
        for (UUID aid : linkedAssetIds) {
            AssetScopedFreshnessSummary summary = evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                    projectId, asOf, freshnessWindowDays, aid);
            freshnessStateByAsset.put(aid, summary.dominantState());
        }

        // 4. Compose entries with filters and stale rollup
        List<ThreatModelWorkspaceResult.WorkspaceThreatEntry> entries = new ArrayList<>();
        for (ThreatModel tm : rawEntries) {
            // Optional in-memory filters
            if (stride != null && tm.getStride() != stride) {
                continue;
            }
            if (status != null && tm.getStatus() != status) {
                continue;
            }
            // Optional asset-scope filter: only include if this entry has a link to assetId
            if (assetId != null && !hasAssetLink(linksByTm.getOrDefault(tm.getId(), List.of()), assetId)) {
                continue;
            }

            List<ThreatModelLink> tmLinks = linksByTm.getOrDefault(tm.getId(), List.of());

            List<UUID> entryAssetIds = new ArrayList<>();
            List<ThreatModelWorkspaceResult.WorkspaceLink> controls = new ArrayList<>();
            List<ThreatModelWorkspaceResult.WorkspaceLink> requirements = new ArrayList<>();

            for (ThreatModelLink link : tmLinks) {
                switch (link.getTargetType()) {
                    case ASSET -> {
                        if (link.getTargetEntityId() != null) {
                            entryAssetIds.add(link.getTargetEntityId());
                        }
                    }
                    case CONTROL -> controls.add(toWorkspaceLink(link));
                    case REQUIREMENT -> requirements.add(toWorkspaceLink(link));
                    default -> {
                        // Other link types (RISK_SCENARIO, OBSERVATION, RISK_ASSESSMENT_RESULT,
                        // VERIFICATION_RESULT, FINDING, ARCHITECTURE_MODEL, CODE, ISSUE, EVIDENCE,
                        // EXTERNAL) are not included in the workspace result.
                    }
                }
            }

            String staleIndicator = rollupStaleIndicator(entryAssetIds, freshnessStateByAsset);

            entries.add(new ThreatModelWorkspaceResult.WorkspaceThreatEntry(
                    tm.getId(),
                    tm.getUid(),
                    tm.getTitle(),
                    tm.getStatus(),
                    tm.getStride(),
                    entryAssetIds,
                    controls,
                    requirements,
                    staleIndicator));
        }

        log.info(
                "threat_model_workspace assembled: project={} assets={} flows={} entries={}",
                projectId,
                workspaceAssets.size(),
                flows.size(),
                entries.size());

        return new ThreatModelWorkspaceResult(workspaceAssets, flows, entries);
    }

    private static boolean hasAssetLink(List<ThreatModelLink> links, UUID assetId) {
        for (ThreatModelLink link : links) {
            if (link.getTargetType() == ThreatModelLinkTargetType.ASSET && assetId.equals(link.getTargetEntityId())) {
                return true;
            }
        }
        return false;
    }

    private static ThreatModelWorkspaceResult.WorkspaceLink toWorkspaceLink(ThreatModelLink link) {
        return new ThreatModelWorkspaceResult.WorkspaceLink(
                link.getTargetEntityId(), link.getTargetIdentifier(), link.getTargetTitle(), link.getTargetUrl());
    }

    /**
     * Rolls up the stale indicator for an entry by taking the worst dominantState
     * across linked asset ids. Severity order: EXPIRED > STALE > SUPERSEDED > FRESH >
     * NO_OBSERVATIONS. When there are no linked assets the result is NO_OBSERVATIONS.
     */
    private static String rollupStaleIndicator(List<UUID> assetIds, Map<UUID, String> freshnessStateByAsset) {
        if (assetIds.isEmpty()) {
            return NO_OBSERVATIONS;
        }
        String worst = NO_OBSERVATIONS;
        for (UUID aid : assetIds) {
            String state = freshnessStateByAsset.getOrDefault(aid, NO_OBSERVATIONS);
            if (severity(state) > severity(worst)) {
                worst = state;
            }
        }
        return worst;
    }

    private static int severity(String state) {
        int idx = STATE_SEVERITY.indexOf(state);
        return idx < 0 ? 0 : idx;
    }
}
