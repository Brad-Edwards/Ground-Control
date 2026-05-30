package com.keplerops.groundcontrol.domain.evidence.service;

import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.verification.state.AssuranceLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain result for the Evidence and State Explorer per GC-Q012.
 *
 * <p>This is a <strong>read-only composition</strong> over existing aggregates — no new JPA
 * aggregate, table, or migration is introduced. Freshness state, ageDays, and the counts roll-up are
 * delegated wholesale to {@code EvidenceFreshnessAnalysisService.analyze} (GC-L007) so the explorer
 * never re-derives freshness logic; this result only enriches those items with provenance (evidence
 * source refs, observation source/confidence/evidenceRef) and downstream finding impact.
 */
public record EvidenceExplorerResult(
        List<ExplorerArtifact> evidenceArtifacts,
        List<ExplorerObservation> observations,
        FreshnessCounts counts,
        List<String> limitations) {

    public int artifactCount() {
        return evidenceArtifacts.size();
    }

    public int observationCount() {
        return observations.size();
    }

    /** An evidence artifact with provenance sources, freshness, and downstream finding impact. */
    public record ExplorerArtifact(
            UUID id,
            String uid,
            String title,
            EvidenceType evidenceType,
            String derivationMethod,
            Instant derivedAt,
            String derivedBy,
            AssuranceLevel assuranceLevel,
            String confidence,
            UUID supersededByArtifactId,
            String freshnessState,
            long ageDays,
            List<ExplorerSource> sources,
            List<ExplorerFindingRef> downstreamFindings) {}

    /** A provenance source reference on an evidence artifact. */
    public record ExplorerSource(
            EvidenceSourceKind sourceKind, UUID sourceEntityId, String sourceIdentifier, String role) {}

    /** An observation with provenance, freshness, affected asset, and downstream finding impact. */
    public record ExplorerObservation(
            UUID id,
            UUID assetId,
            String assetUid,
            String category,
            String observationKey,
            String observationValue,
            String source,
            String confidence,
            String evidenceRef,
            Instant observedAt,
            Instant expiresAt,
            String freshnessState,
            long ageDays,
            List<ExplorerFindingRef> downstreamFindings) {}

    /** A finding downstream of an evidence artifact or observation. */
    public record ExplorerFindingRef(
            UUID id, String uid, String title, FindingSeverity severity, FindingStatus status) {}

    /** Evidence-freshness roll-up, mirrored from the freshness analysis (GC-L007). */
    public record FreshnessCounts(int fresh, int stale, int expired, int superseded, int currentlyValid) {}
}
