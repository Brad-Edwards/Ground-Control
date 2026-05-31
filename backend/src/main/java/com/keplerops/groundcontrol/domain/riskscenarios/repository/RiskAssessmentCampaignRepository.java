package com.keplerops.groundcontrol.domain.riskscenarios.repository;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentCampaign;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskAssessmentCampaignRepository extends JpaRepository<RiskAssessmentCampaign, UUID> {

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    Optional<RiskAssessmentCampaign> findByIdAndProjectId(UUID id, UUID projectId);

    List<RiskAssessmentCampaign> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
