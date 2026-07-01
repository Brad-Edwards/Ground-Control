package com.keplerops.groundcontrol.infrastructure.campaign;

import com.keplerops.groundcontrol.domain.evidence.campaign.service.EvidenceCampaignService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled driver for GC-S005 evidence campaigns. Two ticks: the due-campaign
 * sweep claims and executes every ACTIVE campaign whose next run is due, and the
 * prune tick ages out finished runs past each campaign's retention horizon.
 *
 * <p>Both run synchronously on the scheduling thread; a long collection blocks
 * the next tick, matching the existing {@code ScheduledSweepRunner} convention.
 */
@Component
@ConditionalOnProperty(name = "groundcontrol.evidence.campaign.enabled", havingValue = "true")
public class EvidenceCampaignRunner {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCampaignRunner.class);

    private final EvidenceCampaignService service;

    public EvidenceCampaignRunner(EvidenceCampaignService service) {
        this.service = service;
    }

    @Scheduled(cron = "${groundcontrol.evidence.campaign.cron:0 0 * * * *}")
    public void runDueCampaigns() {
        int executed = service.runDueCampaigns(Instant.now());
        log.info("evidence_campaign_sweep_finished: executed={}", executed);
    }

    @Scheduled(cron = "${groundcontrol.evidence.campaign.prune-cron:0 30 3 * * *}")
    public void pruneExpiredRuns() {
        int pruned = service.pruneExpiredRuns(Instant.now());
        log.info("evidence_campaign_prune_finished: pruned={}", pruned);
    }
}
