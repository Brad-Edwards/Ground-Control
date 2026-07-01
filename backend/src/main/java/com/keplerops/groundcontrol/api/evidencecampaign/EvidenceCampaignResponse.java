package com.keplerops.groundcontrol.api.evidencecampaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaign;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignFrequency;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceCampaignResponse(
        UUID id,
        String projectIdentifier,
        String uid,
        String name,
        EvidenceCampaignFrequency frequency,
        EvidenceCampaignStatus status,
        String adapterName,
        String scopeType,
        String schemaId,
        String connectionProfileId,
        String connectionEndpoint,
        String credentialRef,
        Map<String, Object> scopeCriteria,
        List<UUID> targetControlIds,
        Integer retentionDays,
        Instant nextRunAt,
        Instant lastRunAt,
        Instant createdAt,
        Instant updatedAt) {

    public static EvidenceCampaignResponse from(EvidenceCampaign campaign) {
        return new EvidenceCampaignResponse(
                campaign.getId(),
                campaign.getProject().getIdentifier(),
                campaign.getUid(),
                campaign.getName(),
                campaign.getFrequency(),
                campaign.getStatus(),
                campaign.getAdapterName(),
                campaign.getScopeType(),
                campaign.getSchemaId(),
                campaign.getConnectionProfileId(),
                campaign.getConnectionEndpoint(),
                // credentialRef is an indirection key, not a raw secret; safe to echo back.
                campaign.getCredentialRef(),
                campaign.getScopeCriteria(),
                campaign.getTargetControlIds(),
                campaign.getRetentionDays(),
                campaign.getNextRunAt(),
                campaign.getLastRunAt(),
                campaign.getCreatedAt(),
                campaign.getUpdatedAt());
    }
}
