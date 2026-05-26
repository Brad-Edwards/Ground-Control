package com.keplerops.groundcontrol.domain.riskscenarios.events;

/**
 * Published by {@code ControlService} on status transition or
 * effectiveness change (GC-T004 / C8, issue #863). Other control
 * fields are deliberately not in scope — only status + effectiveness
 * count as mitigation-context changes per the preflight.
 */
public record ControlStateChangedEvent(ReassessmentSignal signal) {}
