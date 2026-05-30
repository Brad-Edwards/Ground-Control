package com.keplerops.groundcontrol.infrastructure.compliance;

import com.keplerops.groundcontrol.domain.compliance.service.ComplianceDriftDetectorService;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Conditional wiring for the GC-I004 evidence-expiry sweep job.
 *
 * <p>Disabled by default (matches the {@link AuditRetentionConfig} pattern of
 * not running scheduled jobs in test/dev unless explicitly enabled). Set
 * {@code groundcontrol.compliance.evidence-expiry-enabled=true} to wire the
 * job; the cron expression is overridable via
 * {@code groundcontrol.compliance.evidence-expiry-cron}.
 */
@Configuration
@ConditionalOnProperty(value = "groundcontrol.compliance.evidence-expiry-enabled", havingValue = "true")
@EnableScheduling
public class EvidenceExpirySweepConfig {

    private final EvidenceExpirySweepJob job;
    private final ComplianceDriftDetectorService detector;

    public EvidenceExpirySweepConfig(EvidenceExpirySweepJob job, ComplianceDriftDetectorService detector) {
        this.job = job;
        this.detector = detector;
    }

    @Bean
    @ConditionalOnMissingBean
    static Clock complianceClock() {
        return Clock.systemUTC();
    }

    @Bean
    static EvidenceExpirySweepJob evidenceExpirySweepJob(
            EvidenceArtifactRepository evidenceArtifactRepository,
            ApplicationEventPublisher eventPublisher,
            @Autowired Clock clock) {
        return new EvidenceExpirySweepJob(evidenceArtifactRepository, eventPublisher, clock);
    }

    /**
     * Wire the sweep job's {@code lastSweepAt} accessor into the detector's
     * liveness probe. Keeps the api -> infrastructure direct dependency out
     * of the controller path (ArchUnit forbids it); the detector exposes a
     * narrow supplier seam instead.
     */
    @PostConstruct
    void wireLastSweepSupplier() {
        detector.setLastSweepAtSupplier(job::lastSweepAt);
    }
}
