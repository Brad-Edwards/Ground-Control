package com.keplerops.groundcontrol.domain.evidence.campaign.repository;

import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaignRun;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EvidenceCampaignRunRepository extends JpaRepository<EvidenceCampaignRun, UUID> {

    @Query("SELECT r FROM EvidenceCampaignRun r WHERE r.campaign.id = :campaignId AND r.project.id = :projectId "
            + "ORDER BY r.windowStart DESC")
    List<EvidenceCampaignRun> findByCampaignIdAndProjectIdOrderByWindowStartDesc(
            @Param("campaignId") UUID campaignId, @Param("projectId") UUID projectId);

    /**
     * Delete finished runs for one campaign whose {@code finishedAt} precedes
     * the retention cutoff. In-flight runs ({@code finishedAt} null) are never
     * pruned. Returns the number of rows removed.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM EvidenceCampaignRun r WHERE r.campaign.id = :campaignId "
            + "AND r.finishedAt IS NOT NULL AND r.finishedAt < :cutoff")
    int deleteFinishedRunsBefore(@Param("campaignId") UUID campaignId, @Param("cutoff") Instant cutoff);
}
