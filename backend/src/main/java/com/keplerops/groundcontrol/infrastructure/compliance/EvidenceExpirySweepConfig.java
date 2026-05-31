package com.keplerops.groundcontrol.infrastructure.compliance;

import com.keplerops.groundcontrol.domain.compliance.service.ComplianceDriftDetectorService;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    /**
     * Per-artifact dispatch template: the sweep job uses this to wrap each
     * synchronous {@code publishEvent} call so the detector listener runs in
     * its own writable transaction (REQUIRES_NEW). Without REQUIRES_NEW the
     * detector's drift-event INSERT either silently fails (joining a
     * read-only context) or rolls back the entire sweep batch on a single
     * listener failure — exactly the "stalled monitor silently reports
     * compliant" mode the cluster scope flagged.
     */
    @Bean
    static TransactionTemplate evidenceExpiryDispatchTransactionTemplate(
            PlatformTransactionManager transactionManager) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Bean
    static EvidenceExpirySweepJob evidenceExpirySweepJob(
            EvidenceArtifactRepository evidenceArtifactRepository,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate evidenceExpiryDispatchTransactionTemplate,
            Clock clock) {
        return new EvidenceExpirySweepJob(
                evidenceArtifactRepository, eventPublisher, evidenceExpiryDispatchTransactionTemplate, clock);
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
