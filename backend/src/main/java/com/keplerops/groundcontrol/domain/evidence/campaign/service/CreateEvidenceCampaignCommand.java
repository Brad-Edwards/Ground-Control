package com.keplerops.groundcontrol.domain.evidence.campaign.service;

import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignFrequency;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateEvidenceCampaignCommand(
        UUID projectId,
        String uid,
        String name,
        EvidenceCampaignFrequency frequency,
        String adapterName,
        String scopeType,
        String schemaId,
        String connectionProfileId,
        String connectionEndpoint,
        String credentialRef,
        Map<String, Object> scopeCriteria,
        List<UUID> targetControlIds,
        Integer retentionDays,
        Instant firstRunAt) {}
