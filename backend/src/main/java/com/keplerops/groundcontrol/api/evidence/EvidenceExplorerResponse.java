package com.keplerops.groundcontrol.api.evidence;

import com.keplerops.groundcontrol.domain.evidence.service.EvidenceExplorerResult;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.verification.state.AssuranceLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HTTP DTO mirror of {@link EvidenceExplorerResult} for the
 * {@code GET /api/v1/evidence-artifacts/explorer} endpoint (GC-Q012).
 */
public record EvidenceExplorerResponse(
        List<ExplorerArtifactDto> evidenceArtifacts,
        List<ExplorerObservationDto> observations,
        FreshnessCountsDto counts,
        List<String> limitations,
        int artifactCount,
        int observationCount) {

    public static EvidenceExplorerResponse from(EvidenceExplorerResult result) {
        List<ExplorerArtifactDto> artifacts = result.evidenceArtifacts().stream()
                .map(EvidenceExplorerResponse::toArtifactDto)
                .toList();
        List<ExplorerObservationDto> observations = result.observations().stream()
                .map(EvidenceExplorerResponse::toObservationDto)
                .toList();
        FreshnessCountsDto counts = new FreshnessCountsDto(
                result.counts().fresh(),
                result.counts().stale(),
                result.counts().expired(),
                result.counts().superseded(),
                result.counts().currentlyValid());
        return new EvidenceExplorerResponse(
                artifacts,
                observations,
                counts,
                result.limitations(),
                result.artifactCount(),
                result.observationCount());
    }

    private static ExplorerArtifactDto toArtifactDto(EvidenceExplorerResult.ExplorerArtifact a) {
        return new ExplorerArtifactDto(
                a.id(),
                a.uid(),
                a.title(),
                a.evidenceType(),
                a.derivationMethod(),
                a.derivedAt(),
                a.derivedBy(),
                a.assuranceLevel(),
                a.confidence(),
                a.supersededByArtifactId(),
                a.freshnessState(),
                a.ageDays(),
                a.sources().stream()
                        .map(s -> new ExplorerSourceDto(
                                s.sourceKind(), s.sourceEntityId(), s.sourceIdentifier(), s.role()))
                        .toList(),
                a.downstreamFindings().stream()
                        .map(EvidenceExplorerResponse::toFindingRefDto)
                        .toList());
    }

    private static ExplorerObservationDto toObservationDto(EvidenceExplorerResult.ExplorerObservation o) {
        return new ExplorerObservationDto(
                o.id(),
                o.assetId(),
                o.assetUid(),
                o.category(),
                o.observationKey(),
                o.observationValue(),
                o.source(),
                o.confidence(),
                o.evidenceRef(),
                o.observedAt(),
                o.expiresAt(),
                o.freshnessState(),
                o.ageDays(),
                o.downstreamFindings().stream()
                        .map(EvidenceExplorerResponse::toFindingRefDto)
                        .toList());
    }

    private static ExplorerFindingRefDto toFindingRefDto(EvidenceExplorerResult.ExplorerFindingRef f) {
        return new ExplorerFindingRefDto(f.id(), f.uid(), f.title(), f.severity(), f.status());
    }

    public record ExplorerArtifactDto(
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
            List<ExplorerSourceDto> sources,
            List<ExplorerFindingRefDto> downstreamFindings) {}

    public record ExplorerSourceDto(
            EvidenceSourceKind sourceKind, UUID sourceEntityId, String sourceIdentifier, String role) {}

    public record ExplorerObservationDto(
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
            List<ExplorerFindingRefDto> downstreamFindings) {}

    public record ExplorerFindingRefDto(
            UUID id, String uid, String title, FindingSeverity severity, FindingStatus status) {}

    public record FreshnessCountsDto(int fresh, int stale, int expired, int superseded, int currentlyValid) {}
}
