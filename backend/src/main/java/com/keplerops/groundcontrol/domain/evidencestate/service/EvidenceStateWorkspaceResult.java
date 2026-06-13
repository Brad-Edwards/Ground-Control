package com.keplerops.groundcontrol.domain.evidencestate.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only composition for the Evidence and State Explorer per GC-Q012.
 *
 * <p>The records below intentionally expose summaries and links, not raw
 * evidence payloads or storage-specific details. Freshness state is copied from
 * {@code EvidenceFreshnessAnalysisService}; this result is not a second source
 * of truth for freshness.
 */
public record EvidenceStateWorkspaceResult(
        List<WorkspaceAsset> assets,
        List<EvidenceArtifactItem> evidenceArtifacts,
        List<ObservationItem> observations,
        EvidenceFreshnessCounts counts,
        List<String> limitations) {

    public int assetCount() {
        return assets.size();
    }

    public int artifactCount() {
        return evidenceArtifacts.size();
    }

    public int observationCount() {
        return observations.size();
    }

    public record WorkspaceAsset(UUID id, String uid, String name, String assetType, boolean boundary) {}

    public record WorkspaceLink(UUID targetEntityId, String targetIdentifier, String targetTitle, String targetUrl) {}

    public record ProvenanceSource(
            String sourceKind, UUID sourceEntityId, String sourceIdentifier, String role, String label) {}

    public record EvidenceArtifactItem(
            UUID id,
            String uid,
            String title,
            String summaryPreview,
            String evidenceType,
            Instant derivedAt,
            long ageDays,
            String freshnessState,
            UUID supersededByArtifactId,
            String derivedBy,
            String assuranceLevel,
            String confidence,
            List<ProvenanceSource> sources,
            List<WorkspaceLink> affectedAssets,
            List<WorkspaceLink> linkedControls,
            List<WorkspaceLink> downstreamAssessments,
            List<WorkspaceLink> linkedFindings) {}

    public record ObservationItem(
            UUID id,
            UUID assetId,
            String assetUid,
            String category,
            String observationKey,
            String valuePreview,
            String source,
            String evidenceRef,
            Instant observedAt,
            Instant expiresAt,
            long ageDays,
            String freshnessState,
            String confidence,
            List<WorkspaceLink> evidenceArtifacts,
            List<WorkspaceLink> downstreamAssessments,
            List<WorkspaceLink> linkedFindings) {}

    public record EvidenceFreshnessCounts(int fresh, int stale, int expired, int superseded, int currentlyValid) {}
}
