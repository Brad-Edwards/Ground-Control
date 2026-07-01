package com.keplerops.groundcontrol.api.evidencecampaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaignRun;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceCampaignRunResponse(
        UUID id,
        UUID campaignId,
        String projectIdentifier,
        EvidenceCampaignRunStatus status,
        Instant windowStart,
        Instant windowEnd,
        Instant startedAt,
        Instant finishedAt,
        int artifactCount,
        int errorCount,
        String sanitizedError,
        List<UUID> producedArtifactIds,
        Instant createdAt,
        Instant updatedAt) {

    public static EvidenceCampaignRunResponse from(EvidenceCampaignRun run) {
        return new EvidenceCampaignRunResponse(
                run.getId(),
                run.getCampaign().getId(),
                run.getProject().getIdentifier(),
                run.getStatus(),
                run.getWindowStart(),
                run.getWindowEnd(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getArtifactCount(),
                run.getErrorCount(),
                run.getSanitizedError(),
                run.getProducedArtifactIds(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }
}
