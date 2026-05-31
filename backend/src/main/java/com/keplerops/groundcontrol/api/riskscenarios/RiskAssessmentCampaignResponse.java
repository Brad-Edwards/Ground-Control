package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentCampaign;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CampaignPhase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RiskAssessmentCampaignResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        String uid,
        String title,
        String owner,
        String objective,
        CampaignPhase phase,
        UUID methodologyProfileId,
        UUID appetiteProfileId,
        Instant scheduledStart,
        Instant scheduledEnd,
        Map<String, Object> scope,
        Map<String, Object> approvalMetadata,
        List<String> scopedAssetIds,
        Instant createdAt,
        Instant updatedAt) {

    public static RiskAssessmentCampaignResponse from(RiskAssessmentCampaign campaign) {
        return new RiskAssessmentCampaignResponse(
                campaign.getId(),
                GraphIds.nodeId(GraphEntityType.RISK_ASSESSMENT_CAMPAIGN, campaign.getId()),
                campaign.getProject().getIdentifier(),
                campaign.getUid(),
                campaign.getTitle(),
                campaign.getOwner(),
                campaign.getObjective(),
                campaign.getPhase(),
                campaign.getMethodologyProfile() != null
                        ? campaign.getMethodologyProfile().getId()
                        : null,
                campaign.getAppetiteProfile() != null
                        ? campaign.getAppetiteProfile().getId()
                        : null,
                campaign.getScheduledStart(),
                campaign.getScheduledEnd(),
                campaign.getScope(),
                campaign.getApprovalMetadata(),
                campaign.getScopedAssetIds(),
                campaign.getCreatedAt(),
                campaign.getUpdatedAt());
    }
}
