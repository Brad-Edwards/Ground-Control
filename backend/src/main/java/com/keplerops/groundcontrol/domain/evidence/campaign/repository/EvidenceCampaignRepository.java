package com.keplerops.groundcontrol.domain.evidence.campaign.repository;

import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaign;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EvidenceCampaignRepository extends JpaRepository<EvidenceCampaign, UUID> {

    @Query("SELECT c FROM EvidenceCampaign c WHERE c.id = :id AND c.project.id = :projectId")
    Optional<EvidenceCampaign> findByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    @Query("SELECT c FROM EvidenceCampaign c WHERE c.project.id = :projectId ORDER BY c.createdAt DESC, c.uid ASC")
    List<EvidenceCampaign> findByProjectIdOrderByCreatedAtDesc(@Param("projectId") UUID projectId);

    List<EvidenceCampaign> findByStatusAndNextRunAtLessThanEqual(EvidenceCampaignStatus status, Instant cutoff);

    /**
     * Claim a due campaign by atomically advancing its scheduling cursor only
     * when {@code nextRunAt} still equals the value the sweep observed AND the
     * campaign is still in the {@code expectedStatus} (ACTIVE). Two concurrent
     * sweep ticks both read the same due cursor, but only one of their
     * conditional updates affects a row; the loser observes 0 affected rows and
     * skips the window — the same optimistic-claim pattern used for the
     * evidence-artifact supersede-once invariant. Folding status into the
     * predicate makes pause an atomic lifecycle boundary: a campaign paused
     * between the due-select and the claim is no longer ACTIVE, so the claim
     * affects 0 rows and the sweep does not execute a paused campaign.
     */
    @Transactional
    @Modifying
    @Query("UPDATE EvidenceCampaign c SET c.nextRunAt = :next, c.lastRunAt = :now "
            + "WHERE c.id = :id AND c.nextRunAt = :observed AND c.status = :expectedStatus")
    int markClaimedIfDue(
            @Param("id") UUID id,
            @Param("observed") Instant observed,
            @Param("next") Instant next,
            @Param("now") Instant now,
            @Param("expectedStatus") EvidenceCampaignStatus expectedStatus);
}
