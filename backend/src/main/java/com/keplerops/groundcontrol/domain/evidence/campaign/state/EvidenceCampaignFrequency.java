package com.keplerops.groundcontrol.domain.evidence.campaign.state;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Cadence at which an {@link EvidenceCampaign} collects evidence (GC-S005).
 *
 * <p>{@link #advance(Instant)} computes the next scheduled instant from a
 * reference point using calendar arithmetic anchored at UTC, so month- and
 * quarter-length steps respect varying month lengths rather than using a fixed
 * 30-day approximation.
 */
public enum EvidenceCampaignFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    QUARTERLY;

    /**
     * Return the next run instant after {@code from}, using UTC calendar
     * arithmetic: +1 day, +7 days, +1 month, or +3 months.
     */
    public Instant advance(Instant from) {
        if (from == null) {
            throw new IllegalArgumentException("from must not be null");
        }
        ZonedDateTime base = from.atZone(ZoneOffset.UTC);
        return switch (this) {
            case DAILY -> base.plusDays(1).toInstant();
            case WEEKLY -> base.plusDays(7).toInstant();
            case MONTHLY -> base.plusMonths(1).toInstant();
            case QUARTERLY -> base.plusMonths(3).toInstant();
        };
    }
}
