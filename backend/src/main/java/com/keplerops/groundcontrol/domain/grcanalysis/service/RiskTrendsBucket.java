package com.keplerops.groundcontrol.domain.grcanalysis.service;

/**
 * Bucket sizes for the GC-T008 risk trend projection. Trends bucket Envers
 * revisions of {@code RiskRegisterRecord} into fixed intervals; weekly /
 * monthly / quarterly are the supported sizes today.
 */
public enum RiskTrendsBucket {
    WEEK,
    MONTH,
    QUARTER
}
