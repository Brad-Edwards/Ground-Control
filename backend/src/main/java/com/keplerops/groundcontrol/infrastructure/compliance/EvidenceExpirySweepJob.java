package com.keplerops.groundcontrol.infrastructure.compliance;

import com.keplerops.groundcontrol.domain.compliance.events.EvidenceExpiryEvent;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-I004 scheduled sweep that emits one {@link EvidenceExpiryEvent} per
 * evidence artifact whose {@code expiresAt} has elapsed and which has not
 * been observed expired before.
 *
 * <p>Lives alongside {@link AuditRetentionJob} so the existing
 * {@code @Conditional}/{@code @EnableScheduling} wiring pattern is reused.
 * The job is read-only at the evidence level — it does NOT mutate any
 * artifact rows; append-only is preserved. The detector receives the event
 * synchronously and decides whether to publish a drift event (idempotency
 * check on {@code compliance_drift_event}).
 *
 * <p>Liveness telemetry: the job records the last successful sweep
 * timestamp via {@link #lastSweepAt()}; {@code ComplianceDriftController}
 * surfaces it. A stalled scheduler is then directly observable, preventing
 * a silent "compliant" report while the monitor is dead.
 */
public class EvidenceExpirySweepJob {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExpirySweepJob.class);

    private final EvidenceArtifactRepository evidenceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final AtomicReference<Instant> lastSweepAt = new AtomicReference<>();

    public EvidenceExpirySweepJob(
            EvidenceArtifactRepository evidenceRepository, ApplicationEventPublisher eventPublisher, Clock clock) {
        this.evidenceRepository = evidenceRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Sweep entrypoint. Default cron is every 15 minutes; deployments can
     * override via {@code groundcontrol.compliance.evidence-expiry-cron}.
     */
    @Scheduled(cron = "${groundcontrol.compliance.evidence-expiry-cron:0 */15 * * * *}")
    @Transactional(readOnly = true)
    public void sweep() {
        var now = Instant.now(clock);
        var expired = evidenceRepository.findExpiredAsOf(now);
        int dispatched = 0;
        int failed = 0;
        for (var artifact : expired) {
            try {
                eventPublisher.publishEvent(new EvidenceExpiryEvent(
                        artifact.getProject().getId(), artifact.getId(), artifact.getUid(), artifact.getExpiresAt()));
                dispatched++;
            } catch (RuntimeException ex) {
                // Per the synchronous-event contract, a listener failure
                // rolls back its own write — but the sweep itself MUST keep
                // running so a single bad artifact does not silence the
                // entire monitor. We log + count, never swallow silently.
                failed++;
                log.warn(
                        "evidence_expiry_dispatch_failed: project_id={} artifact_id={} uid={} error={}",
                        artifact.getProject().getId(),
                        artifact.getId(),
                        artifact.getUid(),
                        ex.getClass().getSimpleName());
            }
        }
        lastSweepAt.set(now);
        log.info(
                "evidence_expiry_sweep_completed: candidates={} dispatched={} failed={} sampled_at={}",
                expired.size(),
                dispatched,
                failed,
                now);
    }

    /**
     * Last successful sweep timestamp; {@code null} until the first sweep
     * completes. Surfaced by the drift controller's liveness endpoint.
     */
    public Instant lastSweepAt() {
        return lastSweepAt.get();
    }
}
