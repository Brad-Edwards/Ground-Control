package com.keplerops.groundcontrol.api.compliance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceDriftDetectorService.DetectorLiveness;
import java.time.Instant;

/**
 * Liveness telemetry for the GC-I004 drift detector.
 *
 * <p>{@code lagSeconds} is the duration between {@code lastDetectedAt} and
 * {@code sampledAt}; null when no drift event has ever been published for
 * the project. {@code lastSweepAt} is the most recent successful run of
 * {@code EvidenceExpirySweepJob}; null when the job is disabled or has not
 * run yet.
 *
 * <p>Consumers (dashboards, alerting) read this to detect a stalled monitor
 * — without it, a dead scheduler would silently look "compliant" because no
 * new drift events get published.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComplianceDriftLivenessResponse(
        Instant sampledAt, Instant lastDetectedAt, Instant lastSweepAt, Long lagSeconds, int unacknowledgedCount) {

    public static ComplianceDriftLivenessResponse from(DetectorLiveness liveness) {
        Long lagSeconds =
                liveness.lagSinceLastEvent().map(java.time.Duration::toSeconds).orElse(null);
        return new ComplianceDriftLivenessResponse(
                liveness.sampledAt(),
                liveness.lastDetectedAt(),
                liveness.lastSweepAt(),
                lagSeconds,
                liveness.unacknowledged());
    }
}
