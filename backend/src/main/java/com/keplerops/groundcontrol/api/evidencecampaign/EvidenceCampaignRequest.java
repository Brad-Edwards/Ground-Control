package com.keplerops.groundcontrol.api.evidencecampaign;

import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignFrequency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvidenceCampaignRequest(
        @NotBlank @Size(max = 50) String uid,
        @NotBlank @Size(max = 200) String name,
        @NotNull EvidenceCampaignFrequency frequency,
        @NotBlank @Size(max = 100) String adapterName,
        @NotBlank @Size(max = 120) String scopeType,
        @Size(max = 120) String schemaId,
        @NotBlank @Size(max = 200) String connectionProfileId,
        @NotBlank @Size(max = 500) String connectionEndpoint,
        @NotBlank @Size(max = 200) String credentialRef,
        Map<String, Object> scopeCriteria,
        List<UUID> targetControlIds,
        @Positive Integer retentionDays,
        Instant firstRunAt) {}
