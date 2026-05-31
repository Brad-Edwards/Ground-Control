package com.keplerops.groundcontrol.domain.compliance.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.compliance.events.EvidenceExpiryEvent;
import com.keplerops.groundcontrol.domain.compliance.model.ComplianceDriftEvent;
import com.keplerops.groundcontrol.domain.compliance.repository.ComplianceDriftEventRepository;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftCategory;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftSeverity;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ControlStateChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-I004 compliance drift detector.
 *
 * <p>Listens synchronously to {@link ControlStateChangedEvent} (reusing the
 * existing reassessment signal published by {@code ControlService}) and to
 * {@link EvidenceExpiryEvent} (published by
 * {@code EvidenceExpirySweepJob}). On each handled event the detector
 * writes one {@link ComplianceDriftEvent} row tagged with the source entity
 * and a short, content-free summary.
 *
 * <p>The detector intentionally does NOT mutate controls, evidence
 * artifacts, framework mappings, or posture aggregates. Posture is a read
 * projection over drift events + the existing
 * compliance-framework-mapping aggregate; this service produces signal, not
 * verdict.
 *
 * <p>Per the cluster-wide synchronous-event rule (mirrors
 * {@code ReassessmentSignalService}), every listener here uses
 * {@code @EventListener} — never {@code @TransactionalEventListener}. A
 * listener failure rolls back the publishing mutation. The expiry sweep
 * job wraps each per-artifact dispatch in its own {@code REQUIRES_NEW}
 * {@code TransactionTemplate} execution so the detector receives a fresh
 * writable transaction (its drift-event INSERT is not silently dropped by a
 * read-only context) and so a single detector failure rolls back only that
 * artifact's row — the rest of the sweep batch continues.
 *
 * <p>Liveness telemetry is read via
 * {@link ComplianceDriftEventRepository#findLastDetectedAt(UUID)} and
 * exposed by {@code ComplianceDriftController} so a stalled monitor cannot
 * silently report 'compliant' while signal is missing (security note in the
 * cluster scope).
 */
@Service
@Transactional
public class ComplianceDriftDetectorService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceDriftDetectorService.class);

    /** Max length of the synthesized {@code summary} field; matches DB cap. */
    private static final int MAX_SUMMARY_LENGTH = 1000;

    private static final String NOT_FOUND_MSG = "ComplianceDriftEvent not found: ";

    private final ComplianceDriftEventRepository repository;
    private final ProjectService projectService;

    public ComplianceDriftDetectorService(ComplianceDriftEventRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    @EventListener
    public void onControlStateChanged(ControlStateChangedEvent event) {
        var signal = event.signal();
        // GC-I004: a control status / effectiveness change is the canonical
        // posture-shift signal. We publish one drift event tagged with the
        // changed fields category-summary; severity defaults to WARN on a
        // status change and INFO on an effectiveness-only change.
        var severity = signal.changedFields() != null && signal.changedFields().contains("status")
                ? ComplianceDriftSeverity.WARN
                : ComplianceDriftSeverity.INFO;
        var summary = clamp("Control state changed: fields=" + safeFieldNames(signal.changedFields()));
        publish(
                signal.projectId(),
                new DriftEventSpec(
                        ComplianceDriftCategory.CONTROL_STATE_CHANGED,
                        severity,
                        GraphEntityType.CONTROL.name(),
                        signal.entityId(),
                        summary,
                        signal.occurredAt() != null ? signal.occurredAt() : Instant.now(),
                        /*affectedEntityType=*/ null,
                        /*affectedEntityId=*/ null,
                        /*idempotent=*/ false));
    }

    @EventListener
    public void onEvidenceExpired(EvidenceExpiryEvent event) {
        // Idempotency: skip if we already emitted EVIDENCE_EXPIRED for this
        // artifact. The sweep job batches the read, but a re-run after a
        // crash should not produce duplicate events.
        if (repository.existsBySourceAndCategory(
                event.projectId(),
                ComplianceDriftCategory.EVIDENCE_EXPIRED,
                GraphEntityType.EVIDENCE_ARTIFACT.name(),
                event.evidenceArtifactId())) {
            return;
        }
        var summary = clamp("Evidence artifact expired: uid=" + event.uid());
        publish(
                event.projectId(),
                new DriftEventSpec(
                        ComplianceDriftCategory.EVIDENCE_EXPIRED,
                        ComplianceDriftSeverity.WARN,
                        GraphEntityType.EVIDENCE_ARTIFACT.name(),
                        event.evidenceArtifactId(),
                        summary,
                        event.expiresAt() != null ? event.expiresAt() : Instant.now(),
                        /*affectedEntityType=*/ null,
                        /*affectedEntityId=*/ null,
                        /*idempotent=*/ true));
    }

    /**
     * Optional supplier of the last evidence-expiry sweep timestamp. Wired
     * to {@code EvidenceExpirySweepJob::lastSweepAt} by the infrastructure
     * config when the sweep is enabled. The domain service never reaches
     * into infrastructure itself (ArchUnit forbids it); the supplier is the
     * narrow seam.
     */
    private java.util.function.Supplier<Instant> lastSweepAtSupplier = () -> null;

    public void setLastSweepAtSupplier(java.util.function.Supplier<Instant> supplier) {
        this.lastSweepAtSupplier = supplier == null ? () -> null : supplier;
    }

    /** Read-side liveness probe surfaced by the controller. */
    @Transactional(readOnly = true)
    public DetectorLiveness liveness(UUID projectId) {
        var lastDetected = repository.findLastDetectedAt(projectId).orElse(null);
        var unacknowledged = repository.findUnacknowledgedByProjectId(projectId).size();
        return new DetectorLiveness(Instant.now(), lastDetected, unacknowledged, lastSweepAtSupplier.get());
    }

    @Transactional(readOnly = true)
    public List<ComplianceDriftEvent> listByProject(UUID projectId, ComplianceDriftCategory category) {
        return category == null
                ? repository.findByProjectIdOrderByDetectedAtDesc(projectId)
                : repository.findByProjectIdAndCategoryOrderByDetectedAtDesc(projectId, category);
    }

    @Transactional(readOnly = true)
    public ComplianceDriftEvent getById(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MSG + id));
    }

    /**
     * One-shot acknowledgement. Conditional update enforces the
     * single-acknowledge invariant under concurrent writers; the second
     * caller sees the conflict envelope, not silent success.
     */
    public ComplianceDriftEvent acknowledge(UUID projectId, UUID id) {
        var event = repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MSG + id));
        if (event.getAcknowledgedAt() != null) {
            throw alreadyAcknowledgedConflict(event);
        }
        var actor = ActorHolder.get();
        var now = Instant.now();
        int updated = repository.acknowledgeIfUnset(id, projectId, now, actor);
        if (updated == 0) {
            var refreshed = repository
                    .findByIdAndProjectId(id, projectId)
                    .orElseThrow(() -> new NotFoundException(NOT_FOUND_MSG + id));
            throw alreadyAcknowledgedConflict(refreshed);
        }
        // Re-read so the response reflects the persisted state.
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MSG + id));
    }

    private static ConflictException alreadyAcknowledgedConflict(ComplianceDriftEvent event) {
        return new ConflictException(
                "ComplianceDriftEvent " + event.getId() + " is already acknowledged",
                "compliance_drift_event_already_acknowledged",
                Map.of("id", event.getId().toString()));
    }

    /**
     * Cohesive parameters for a single drift-event write. Introduced to keep
     * the internal {@code publish} method within the 7-parameter limit.
     */
    private record DriftEventSpec(
            ComplianceDriftCategory category,
            ComplianceDriftSeverity severity,
            String sourceEntityType,
            UUID sourceEntityId,
            String summary,
            Instant detectedAt,
            String affectedEntityType,
            UUID affectedEntityId,
            boolean idempotent) {}

    private void publish(UUID projectId, DriftEventSpec spec) {
        var project = projectService.getById(projectId);
        var event = new ComplianceDriftEvent(
                project,
                spec.category(),
                spec.severity(),
                spec.sourceEntityType(),
                spec.sourceEntityId(),
                spec.summary(),
                spec.detectedAt());
        event.setAffectedEntityType(spec.affectedEntityType());
        event.setAffectedEntityId(spec.affectedEntityId());
        event.setDetectedBy(ActorHolder.get());
        repository.save(event);
        if (log.isInfoEnabled()) {
            // Low-cardinality structured log: category, severity, source
            // type + id. Summary text is NOT logged — it may carry artifact
            // uid which is fine, but downstream logging policy is to avoid
            // free-text on the hot path.
            log.info(
                    "compliance_drift_published: project_id={} category={} severity={} source_type={} source_id={} idempotent={}",
                    projectId,
                    spec.category(),
                    spec.severity(),
                    spec.sourceEntityType(),
                    spec.sourceEntityId(),
                    spec.idempotent());
        }
    }

    private static String clamp(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= MAX_SUMMARY_LENGTH) {
            return s;
        }
        return s.substring(0, MAX_SUMMARY_LENGTH);
    }

    private static String safeFieldNames(java.util.Set<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return "(none)";
        }
        // Field names are caller-controlled (publisher's tracked-field set).
        // We deliberately do not echo old/new VALUES; only the field-name set
        // joins the summary so we don't leak content.
        return String.join(",", new java.util.TreeSet<>(fields));
    }

    /**
     * Liveness snapshot: when the detector was sampled, when it last
     * produced an event, how many events remain unacknowledged, and (when
     * the sweep job is wired) when the most recent expiry sweep ran.
     * {@code lastDetectedAt} is null when no event has been produced for
     * the project yet; {@code lastSweepAt} is null when the sweep is
     * disabled or has not yet run.
     */
    public record DetectorLiveness(Instant sampledAt, Instant lastDetectedAt, int unacknowledged, Instant lastSweepAt) {

        public Optional<java.time.Duration> lagSinceLastEvent() {
            if (lastDetectedAt == null) {
                return Optional.empty();
            }
            return Optional.of(java.time.Duration.between(lastDetectedAt, sampledAt));
        }
    }
}
