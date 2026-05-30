package com.keplerops.groundcontrol.domain.grcanalysis.service;

/**
 * Grouping axes for the GC-T008 risk distribution projection. Reflects the
 * GC-T008 requirement statement: distribution by category, status, asset
 * class, owner, or criticality.
 */
public enum RiskDistributionGroupBy {
    CATEGORY,
    STATUS,
    OWNER,
    ASSET_CRITICALITY
}
