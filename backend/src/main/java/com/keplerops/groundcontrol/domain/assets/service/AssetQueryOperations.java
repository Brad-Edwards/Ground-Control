package com.keplerops.groundcontrol.domain.assets.service;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import java.util.List;
import java.util.UUID;

/**
 * Read-side listing and filtering of operational assets.
 *
 * Split out of {@link AssetService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class AssetQueryOperations {

    private final OperationalAssetRepository assetRepository;

    AssetQueryOperations(OperationalAssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    List<OperationalAsset> listByProject(UUID projectId) {
        return assetRepository.findByProjectIdAndArchivedAtIsNull(projectId);
    }

    @SuppressWarnings("java:S107") // JPA @Query needs each @Param explicit; reflected on the public method
    List<OperationalAsset> listByProjectAndFilters(
            UUID projectId,
            AssetType assetType,
            String owner,
            String steward,
            com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment environment,
            com.keplerops.groundcontrol.domain.assets.state.AssetCriticality criticality,
            com.keplerops.groundcontrol.domain.assets.state.AssetScope scopeDesignation,
            String subtype,
            com.keplerops.groundcontrol.domain.assets.state.KnowledgeState knowledgeState) {
        return assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
                projectId,
                assetType,
                owner,
                steward,
                environment,
                criticality,
                scopeDesignation,
                subtype,
                knowledgeState);
    }

    /**
     * @deprecated GC-M018 added the {@code knowledgeState} filter facet.
     *     Callers should adopt the 9-arg overload so the knowledgeState query
     *     parameter is honored. Retained for source compatibility with
     *     pre-GC-M018 callers. Suppressed: S1133 (don't forget to remove
     *     deprecated code) — removal is tied to all callers migrating off
     *     this overload, which we are explicitly NOT requiring in this PR.
     */
    @SuppressWarnings({"java:S107", "java:S1133"})
    @Deprecated(forRemoval = false)
    List<OperationalAsset> listByProjectAndFilters(
            UUID projectId,
            AssetType assetType,
            String owner,
            String steward,
            com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment environment,
            com.keplerops.groundcontrol.domain.assets.state.AssetCriticality criticality,
            com.keplerops.groundcontrol.domain.assets.state.AssetScope scopeDesignation,
            String subtype) {
        return assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
                projectId, assetType, owner, steward, environment, criticality, scopeDesignation, subtype, null);
    }

    /**
     * @deprecated GC-M011 added the {@code subtype} filter facet. Callers
     *     should adopt the 9-arg overload so the subtype and knowledgeState
     *     query parameters are honored. Retained for source compatibility
     *     with pre-GC-M011 callers.
     */
    @Deprecated(forRemoval = false)
    List<OperationalAsset> listByProjectAndFilters(
            UUID projectId,
            AssetType assetType,
            String owner,
            String steward,
            com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment environment,
            com.keplerops.groundcontrol.domain.assets.state.AssetCriticality criticality,
            com.keplerops.groundcontrol.domain.assets.state.AssetScope scopeDesignation) {
        // Call the repository directly rather than self-invoking the new
        // overload via `this.` — the @Transactional proxy would be bypassed
        // and Sonar S6809 flags the pattern. The repository call is itself
        // already covered by the class-level @Transactional.
        return assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
                projectId, assetType, owner, steward, environment, criticality, scopeDesignation, null, null);
    }

    List<OperationalAsset> listByProjectAndType(UUID projectId, AssetType assetType) {
        return assetRepository.findByProjectIdAndAssetTypeAndArchivedAtIsNull(projectId, assetType);
    }
}
