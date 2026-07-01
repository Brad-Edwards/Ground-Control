package com.keplerops.groundcontrol.infrastructure.campaign;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scheduling configuration for the GC-S005 evidence-campaign sweep.
 *
 * <p>{@code cron} drives the due-campaign sweep; {@code pruneCron} drives
 * retention pruning of finished runs. Both default to safe off-peak cadences
 * and are only honoured when {@code groundcontrol.evidence.campaign.enabled} is
 * true.
 */
@ConfigurationProperties(prefix = "groundcontrol.evidence.campaign")
public record EvidenceCampaignProperties(boolean enabled, String cron, String pruneCron) {

    public EvidenceCampaignProperties {
        if (cron == null || cron.isBlank()) {
            cron = "0 0 * * * *";
        }
        if (pruneCron == null || pruneCron.isBlank()) {
            pruneCron = "0 30 3 * * *";
        }
    }
}
