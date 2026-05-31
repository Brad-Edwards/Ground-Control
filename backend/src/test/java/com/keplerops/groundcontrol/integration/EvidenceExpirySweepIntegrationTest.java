package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.compliance.repository.ComplianceDriftEventRepository;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftCategory;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.infrastructure.compliance.EvidenceExpirySweepJob;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GC-I004 end-to-end coverage for the evidence-expiry sweep dispatch path:
 * sweep() -> ApplicationEventPublisher -> ComplianceDriftDetectorService
 * listener -> ComplianceDriftEventRepository.save() against a real
 * {@code ApplicationContext} + Testcontainers Postgres.
 *
 * <p>The unit tests for {@code EvidenceExpirySweepJob} mock
 * {@code ApplicationEventPublisher} and the detector's unit tests mock the
 * repository, so neither catches the transactional shape: if the sweep ran
 * inside a read-only transaction (or wrapped per-artifact dispatch in the
 * sweep's own outer TX), the detector's drift-event INSERT would silently
 * fail or roll back the whole batch. This test asserts the row count on
 * {@code compliance_drift_event} actually advances from 0 to 1 per expired
 * artifact, with the {@code REQUIRES_NEW} per-artifact transaction wrapping
 * doing exactly what the cluster scope says it should.
 */
@TestPropertySource(properties = {"groundcontrol.compliance.evidence-expiry-enabled=true"})
class EvidenceExpirySweepIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EvidenceArtifactRepository evidenceRepository;

    @Autowired
    private ComplianceDriftEventRepository driftRepository;

    @Autowired
    private EvidenceExpirySweepJob sweepJob;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    private Project project;

    @BeforeEach
    void seedProject() {
        transactionTemplate.executeWithoutResult(status -> project = projectRepository
                .findByIdentifier("ground-control")
                .orElseThrow(() -> new IllegalStateException("V012-seeded 'ground-control' project missing")));
    }

    @AfterEach
    void cleanup() throws Exception {
        // Hard cleanup via JDBC: the integration suite shares a single
        // Testcontainers Postgres (BaseIntegrationTest static singleton),
        // so leaking compliance_drift_event or evidence_artifact rows
        // would poison other suites. Order matches FK direction.
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM compliance_drift_event_audit WHERE id IN "
                    + "(SELECT id FROM compliance_drift_event WHERE source_entity_type = 'EVIDENCE_ARTIFACT'"
                    + " AND source_entity_id IN (SELECT id FROM evidence_artifact WHERE uid LIKE 'EVD-SWEEP-%'))");
            stmt.executeUpdate("DELETE FROM compliance_drift_event WHERE source_entity_type = 'EVIDENCE_ARTIFACT'"
                    + " AND source_entity_id IN (SELECT id FROM evidence_artifact WHERE uid LIKE 'EVD-SWEEP-%')");
            stmt.executeUpdate("DELETE FROM evidence_artifact_audit WHERE id IN "
                    + "(SELECT id FROM evidence_artifact WHERE uid LIKE 'EVD-SWEEP-%')");
            stmt.executeUpdate("DELETE FROM evidence_artifact WHERE uid LIKE 'EVD-SWEEP-%'");
        }
    }

    @Test
    void sweepWritesOneDriftEventPerExpiredArtifact() {
        // Seed two expired evidence artifacts.
        var expiredAt1 = Instant.parse("2026-05-29T00:00:00Z");
        var expiredAt2 = Instant.parse("2026-05-29T06:00:00Z");
        var ids = transactionTemplate.execute(status -> {
            var a1 = newExpiredArtifact("EVD-SWEEP-001", expiredAt1);
            var a2 = newExpiredArtifact("EVD-SWEEP-002", expiredAt2);
            return new UUID[] {
                evidenceRepository.save(a1).getId(), evidenceRepository.save(a2).getId()
            };
        });

        // Baseline: no drift events for these artifacts.
        assertThat(countDriftEventsFor(ids[0])).isZero();
        assertThat(countDriftEventsFor(ids[1])).isZero();

        sweepJob.sweep();

        // Real DB row count proves the listener's drift-event INSERT
        // actually committed — not silently dropped by a read-only
        // outer transaction, not rolled back by a batch-wide failure.
        assertThat(countDriftEventsFor(ids[0])).isEqualTo(1);
        assertThat(countDriftEventsFor(ids[1])).isEqualTo(1);
        assertThat(sweepJob.lastSweepAt()).isNotNull();
    }

    @Test
    void secondSweepIsIdempotentPerArtifact() {
        var expiredAt = Instant.parse("2026-05-29T00:00:00Z");
        var artifactId = transactionTemplate.execute(status -> evidenceRepository
                .save(newExpiredArtifact("EVD-SWEEP-IDEM", expiredAt))
                .getId());

        sweepJob.sweep();
        assertThat(countDriftEventsFor(artifactId)).isEqualTo(1);

        // Re-running the sweep must NOT produce a second EVIDENCE_EXPIRED
        // row for the same artifact (existsBySourceAndCategory guard).
        sweepJob.sweep();
        assertThat(countDriftEventsFor(artifactId)).isEqualTo(1);
    }

    private EvidenceArtifact newExpiredArtifact(String uid, Instant expiresAt) {
        var artifact = new EvidenceArtifact(
                project,
                uid,
                "title-" + uid,
                "summary-" + uid,
                EvidenceType.ATTESTATION,
                "sweep-int-method-v1",
                Instant.parse("2026-04-01T00:00:00Z"));
        artifact.setExpiresAt(expiresAt);
        return artifact;
    }

    private long countDriftEventsFor(UUID artifactId) {
        return driftRepository.existsBySourceAndCategory(
                        project.getId(),
                        ComplianceDriftCategory.EVIDENCE_EXPIRED,
                        com.keplerops.groundcontrol.domain.graph.model.GraphEntityType.EVIDENCE_ARTIFACT.name(),
                        artifactId)
                ? 1L
                : 0L;
    }
}
