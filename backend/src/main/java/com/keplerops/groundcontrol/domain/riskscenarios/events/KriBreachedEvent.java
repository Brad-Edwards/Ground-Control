package com.keplerops.groundcontrol.domain.riskscenarios.events;

/**
 * GC-T007: Published synchronously by {@code KeyRiskIndicatorService} when a KRI
 * measurement crosses into the RED band.
 *
 * <p>Same contract as {@link TreatmentProgressChangedEvent} et al — synchronous
 * {@code @EventListener} only, NEVER {@code @TransactionalEventListener}. The
 * publishing transaction MUST roll back together with the listener so a KRI
 * breach can never silently miss its reassessment signal (per cross-cluster
 * decision documented in ReassessmentSignalService).
 */
public record KriBreachedEvent(ReassessmentSignal signal) {}
