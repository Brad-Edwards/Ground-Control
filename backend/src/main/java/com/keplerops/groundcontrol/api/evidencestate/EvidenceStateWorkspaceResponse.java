package com.keplerops.groundcontrol.api.evidencestate;

import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EvidenceStateWorkspaceResponse(
        List<WorkspaceAssetDto> assets,
        List<EvidenceArtifactDto> evidenceArtifacts,
        List<ObservationDto> observations,
        EvidenceFreshnessCountsDto counts,
        List<String> limitations,
        int assetCount,
        int artifactCount,
        int observationCount) {

    public static EvidenceStateWorkspaceResponse from(EvidenceStateWorkspaceResult result) {
        return new EvidenceStateWorkspaceResponse(
                result.assets().stream()
                        .map(a -> new WorkspaceAssetDto(a.id(), a.uid(), a.name(), a.assetType(), a.boundary()))
                        .toList(),
                result.evidenceArtifacts().stream()
                        .map(a -> new EvidenceArtifactDto(
                                a.id(),
                                a.uid(),
                                a.title(),
                                a.summaryPreview(),
                                a.evidenceType(),
                                a.derivedAt(),
                                a.ageDays(),
                                a.freshnessState(),
                                a.supersededByArtifactId(),
                                a.derivedBy(),
                                a.assuranceLevel(),
                                a.confidence(),
                                a.sources().stream()
                                        .map(s -> new ProvenanceSourceDto(
                                                s.sourceKind(),
                                                s.sourceEntityId(),
                                                s.sourceIdentifier(),
                                                s.role(),
                                                s.label()))
                                        .toList(),
                                toLinks(a.affectedAssets()),
                                toLinks(a.linkedControls()),
                                toLinks(a.downstreamAssessments()),
                                toLinks(a.linkedFindings())))
                        .toList(),
                result.observations().stream()
                        .map(o -> new ObservationDto(
                                o.id(),
                                o.assetId(),
                                o.assetUid(),
                                o.category(),
                                o.observationKey(),
                                o.valuePreview(),
                                o.source(),
                                o.evidenceRef(),
                                o.observedAt(),
                                o.expiresAt(),
                                o.ageDays(),
                                o.freshnessState(),
                                o.confidence(),
                                toLinks(o.evidenceArtifacts()),
                                toLinks(o.downstreamAssessments()),
                                toLinks(o.linkedFindings())))
                        .toList(),
                new EvidenceFreshnessCountsDto(
                        result.counts().fresh(),
                        result.counts().stale(),
                        result.counts().expired(),
                        result.counts().superseded(),
                        result.counts().currentlyValid()),
                result.limitations(),
                result.assetCount(),
                result.artifactCount(),
                result.observationCount());
    }

    private static List<WorkspaceLinkDto> toLinks(List<EvidenceStateWorkspaceResult.WorkspaceLink> links) {
        return links.stream()
                .map(l ->
                        new WorkspaceLinkDto(l.targetEntityId(), l.targetIdentifier(), l.targetTitle(), l.targetUrl()))
                .toList();
    }

    public record WorkspaceAssetDto(UUID id, String uid, String name, String assetType, boolean boundary) {}

    public record WorkspaceLinkDto(
            UUID targetEntityId, String targetIdentifier, String targetTitle, String targetUrl) {}

    public record ProvenanceSourceDto(
            String sourceKind, UUID sourceEntityId, String sourceIdentifier, String role, String label) {}

    public record EvidenceArtifactDto(
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
            List<ProvenanceSourceDto> sources,
            List<WorkspaceLinkDto> affectedAssets,
            List<WorkspaceLinkDto> linkedControls,
            List<WorkspaceLinkDto> downstreamAssessments,
            List<WorkspaceLinkDto> linkedFindings) {}

    public record ObservationDto(
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
            List<WorkspaceLinkDto> evidenceArtifacts,
            List<WorkspaceLinkDto> downstreamAssessments,
            List<WorkspaceLinkDto> linkedFindings) {}

    public record EvidenceFreshnessCountsDto(int fresh, int stale, int expired, int superseded, int currentlyValid) {}
}
