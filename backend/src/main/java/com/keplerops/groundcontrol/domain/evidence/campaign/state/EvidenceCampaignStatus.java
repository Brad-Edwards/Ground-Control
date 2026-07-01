package com.keplerops.groundcontrol.domain.evidence.campaign.state;

/**
 * Lifecycle state of an {@link EvidenceCampaign} (GC-S005). Only {@code ACTIVE}
 * campaigns are eligible to be claimed and executed by the scheduled sweep;
 * {@code PAUSED} campaigns are skipped until resumed.
 */
public enum EvidenceCampaignStatus {
    ACTIVE,
    PAUSED
}
