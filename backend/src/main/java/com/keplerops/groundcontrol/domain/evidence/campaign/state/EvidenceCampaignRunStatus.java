package com.keplerops.groundcontrol.domain.evidence.campaign.state;

/**
 * Terminal and in-flight states of a single {@link EvidenceCampaignRun}
 * (GC-S005). {@code PARTIAL} captures a run that produced some artifacts but
 * also recorded collection errors; {@code FAILED} captures a run whose adapter
 * collection failed outright.
 */
public enum EvidenceCampaignRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED
}
