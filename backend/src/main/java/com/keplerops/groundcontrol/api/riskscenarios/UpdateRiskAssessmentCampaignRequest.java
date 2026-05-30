package com.keplerops.groundcontrol.api.riskscenarios;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateRiskAssessmentCampaignRequest(
        @Size(max = 200) String title,
        @Size(max = 200) String owner,
        String objective,
        UUID methodologyProfileId,
        UUID appetiteProfileId,
        Instant scheduledStart,
        Instant scheduledEnd,
        Map<String, Object> scope,
        Map<String, Object> approvalMetadata,
        List<String> scopedAssetIds) {}
