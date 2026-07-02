package com.keplerops.groundcontrol.infrastructure.campaign;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates the GC-S005 evidence-campaign scheduler. Scheduling is opt-in via
 * {@code groundcontrol.evidence.campaign.enabled=true}; when disabled the
 * {@link EvidenceCampaignRunner} bean is not registered and no sweep runs.
 */
@Configuration
@ConditionalOnProperty(name = "groundcontrol.evidence.campaign.enabled", havingValue = "true")
@EnableConfigurationProperties(EvidenceCampaignProperties.class)
@EnableScheduling
public class EvidenceCampaignSchedulingConfig {}
