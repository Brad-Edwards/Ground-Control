package com.keplerops.groundcontrol.api.evidencecampaign;

import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignFrequency;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvidenceCampaignUpdateRequest(
        @Size(max = 200) String name,
        EvidenceCampaignFrequency frequency,
        @Size(max = 120) String scopeType,
        @Size(max = 120) String schemaId,
        @Size(max = 200) String connectionProfileId,
        @Size(max = 500) String connectionEndpoint,
        @Size(max = 200) String credentialRef,
        Map<String, Object> scopeCriteria,
        List<UUID> targetControlIds,
        @Positive Integer retentionDays) {}
