package com.keplerops.groundcontrol.domain.riskscenarios.events;

/**
 * The aggregate kind that produced a reassessment-triggering event
 * (GC-T004 / C8, issue #863). Kept separate from the link/resolver
 * target enums so the listener can route on event provenance without
 * importing those bigger surfaces.
 *
 * <p>GC-T007 extension: {@code KEY_RISK_INDICATOR} sources reassessment
 * signals when a KRI measurement crosses into the RED band.
 */
public enum ReassessmentSourceEntityType {
    TREATMENT_PLAN,
    ASSET,
    CONTROL,
    KEY_RISK_INDICATOR
}
