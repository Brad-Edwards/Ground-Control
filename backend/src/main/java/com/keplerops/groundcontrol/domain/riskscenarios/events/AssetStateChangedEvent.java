package com.keplerops.groundcontrol.domain.riskscenarios.events;

/**
 * Published by {@code AssetService} on archive or risk-bearing field
 * updates (GC-T004 / C8, issue #863). The reassessment-trigger
 * category and the changed-field set narrow what the listener acts on.
 */
public record AssetStateChangedEvent(ReassessmentSignal signal) {}
