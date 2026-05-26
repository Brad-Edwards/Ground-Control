package com.keplerops.groundcontrol.domain.riskscenarios.events;

/**
 * The aggregate kind that produced a reassessment-triggering event
 * (GC-T004 / C8, issue #863). Kept separate from the link/resolver
 * target enums so the listener can route on event provenance without
 * importing those bigger surfaces.
 */
public enum ReassessmentSourceEntityType {
    TREATMENT_PLAN,
    ASSET,
    CONTROL
}
