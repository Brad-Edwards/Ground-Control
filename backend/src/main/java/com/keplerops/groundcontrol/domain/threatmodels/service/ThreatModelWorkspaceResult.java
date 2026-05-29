package com.keplerops.groundcontrol.domain.threatmodels.service;

import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelStatus;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain result for the threat modeling workspace per GC-Q010.
 *
 * <p>This is a read-only composition over existing aggregates — no JPA entities,
 * no web types. The workspace assembles:
 * <ul>
 *   <li>Scoped operational assets (boundaries distinguished by assetType)</li>
 *   <li>Active asset relations (flows)</li>
 *   <li>Threat model entries with their links grouped by target type</li>
 * </ul>
 *
 * <p>Staleness per entry is derived from the evidence freshness of the assets
 * the threat model is linked to (via EvidenceFreshnessAnalysisService). No
 * threat-model review-cadence fields exist in the schema; the stale indicator
 * is the worst dominantState among linked assets. See the service javadoc for
 * the full interpretation rationale.
 */
public record ThreatModelWorkspaceResult(
        List<WorkspaceAsset> assets, List<WorkspaceFlow> flows, List<WorkspaceThreatEntry> entries) {

    public int assetCount() {
        return assets.size();
    }

    public int flowCount() {
        return flows.size();
    }

    public int entryCount() {
        return entries.size();
    }

    /**
     * A scoped operational asset. {@code isBoundary} is true when
     * {@code assetType == BOUNDARY} — callers may use this flag for visual
     * distinction without re-checking the enum.
     */
    public record WorkspaceAsset(UUID id, String uid, String name, AssetType assetType, boolean isBoundary) {}

    /**
     * A directed flow between two scoped assets (an active AssetRelation).
     */
    public record WorkspaceFlow(UUID id, UUID sourceAssetId, UUID targetAssetId, AssetRelationType relationType) {}

    /**
     * A threat model entry with its grouped links and staleness indicator.
     *
     * <p>{@code staleIndicator} is the worst dominantState across all
     * evidence-freshness summaries for linked assets
     * ({@code STALE/EXPIRED > SUPERSEDED > FRESH > NO_OBSERVATIONS}).
     * When there are no linked assets the value is {@code NO_OBSERVATIONS}.
     */
    public record WorkspaceThreatEntry(
            UUID id,
            String uid,
            String title,
            ThreatModelStatus status,
            StrideCategory stride,
            List<UUID> linkedAssetIds,
            List<WorkspaceLink> linkedControls,
            List<WorkspaceLink> linkedRequirements,
            String staleIndicator) {}

    /**
     * A link target (control or requirement). {@code targetEntityId} is the
     * internal UUID when the link points to a first-class entity; may be null
     * for external/not-yet-modeled artifacts. {@code targetIdentifier} is the
     * human-readable external key.
     */
    public record WorkspaceLink(UUID targetEntityId, String targetIdentifier, String targetTitle, String targetUrl) {}
}
