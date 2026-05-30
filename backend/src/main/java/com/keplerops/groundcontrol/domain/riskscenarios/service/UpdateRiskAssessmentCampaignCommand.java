package com.keplerops.groundcontrol.domain.riskscenarios.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateRiskAssessmentCampaignCommand(
        String title,
        String owner,
        String objective,
        UUID methodologyProfileId,
        UUID appetiteProfileId,
        Instant scheduledStart,
        Instant scheduledEnd,
        Map<String, Object> scope,
        Map<String, Object> approvalMetadata,
        List<String> scopedAssetIds) {}
