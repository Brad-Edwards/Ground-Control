package com.keplerops.groundcontrol.domain.evidence.campaign.service;

import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignFrequency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Partial-update command for {@link EvidenceCampaign}. A {@code null} field
 * leaves the corresponding campaign attribute unchanged. The {@code uid},
 * {@code adapterName}, status, and scheduling cursor are not updatable here:
 * uid/adapter are identity-bearing, status changes go through pause/resume, and
 * the cursor is owned by the scheduled sweep.
 */
public record UpdateEvidenceCampaignCommand(
        String name,
        EvidenceCampaignFrequency frequency,
        String scopeType,
        String schemaId,
        String connectionProfileId,
        String connectionEndpoint,
        String credentialRef,
        Map<String, Object> scopeCriteria,
        List<UUID> targetControlIds,
        Integer retentionDays) {}
