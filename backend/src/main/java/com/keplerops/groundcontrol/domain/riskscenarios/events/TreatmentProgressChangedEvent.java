package com.keplerops.groundcontrol.domain.riskscenarios.events;

/**
 * Published by {@code TreatmentPlanService} when a treatment plan's
 * lifecycle status or action-item status histogram changes
 * (GC-T004 / C8, issue #863).
 */
public record TreatmentProgressChangedEvent(ReassessmentSignal signal) {}
