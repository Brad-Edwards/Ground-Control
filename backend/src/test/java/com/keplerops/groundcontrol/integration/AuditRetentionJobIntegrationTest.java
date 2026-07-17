package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.keplerops.groundcontrol.infrastructure.compliance.AuditRetentionJob;
import com.keplerops.groundcontrol.infrastructure.compliance.AuditRetentionProperties;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regression coverage for the live bug fixed in #1309: {@code AuditRetentionJob.AUDIT_TABLES} was
 * a hand-maintained list that had drifted both ways — it named 7 tables V199 had already dropped
 * (crashing the nightly job with a SQL error on the first one) and omitted 29 live audit tables.
 * {@link AuditRetentionJob#discoverAuditTables()} now derives the set from the catalog on every
 * run instead. This test runs against real PostgreSQL (Testcontainers) because the behavior under
 * test is a real {@code information_schema} query, not something a mock can prove.
 */
@Transactional
class AuditRetentionJobIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuditRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new AuditRetentionJob(new AuditRetentionProperties(30, "0 0 3 * * *"), entityManager);
    }

    @Test
    void discoveredTableSetCoversEveryLiveAuditTable() {
        // Ground truth: every table whose name matches the audit-table naming convention. Every
        // one of these declares `rev INTEGER NOT NULL REFERENCES revinfo(rev)` (the repo
        // convention this discovery mechanism relies on), so the two sets must be identical.
        List<String> likeNamedTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name LIKE '%\\_audit' ESCAPE '\\'",
                String.class);

        Set<String> discovered = new HashSet<>(job.discoverAuditTables());

        assertThat(discovered).containsExactlyInAnyOrderElementsOf(likeNamedTables);
        // document_audit is the newest audit table (#1309, Document joining the spine) — pin it
        // explicitly so a regression that only broke the newest table doesn't slip past the
        // set-equality check above for the wrong reason.
        assertThat(discovered).contains("document_audit");
    }

    @Test
    void discoveredTableSetExcludesTablesDroppedByV199() {
        // These 7 names are exactly what the old hardcoded AUDIT_TABLES list still contained after
        // V199 dropped their tables — the direct cause of the nightly job crashing with a SQL
        // error on the first one. The catalog-derived set can never contain a dropped table.
        Set<String> discovered = new HashSet<>(job.discoverAuditTables());

        assertThat(discovered)
                .doesNotContain(
                        "control_effectiveness_assessment_audit",
                        "methodology_profile_audit",
                        "risk_assessment_result_audit",
                        "risk_assessment_result_observation_audit",
                        "risk_register_record_audit",
                        "risk_register_record_scenario_audit",
                        "treatment_plan_audit");
    }

    @Test
    void purgeOldAuditRecords_survivesTheFullLiveSchemaWithoutThrowing() {
        // The direct regression proof: before this fix, running the job against this exact schema
        // threw SQLGrammarException on the first dropped table. retentionDays is generous (no rows
        // should actually be old enough to delete), so this proves the table-set derivation and
        // delete-ordering machinery work end to end against the real schema, not that specific
        // rows get purged.
        assertThatCode(() -> job.purgeOldAuditRecords()).doesNotThrowAnyException();
    }

    @Test
    void purgeOldAuditRecords_deletesExpiredRowsAndKeepsRetainedRows() {
        // The direct behavioral proof the test above cannot provide: seed one revinfo +
        // document_audit pair pinned well outside the 30-day retention window and one pinned well
        // inside it, then assert the purge removes exactly the expired pair and leaves the retained
        // pair untouched. An inverted/off-by-one cutoff comparison in deleteBatched() (e.g.
        // `revtstmp > :cutoff`) would delete the retained pair instead and fail this test.
        //
        // Safe against the shared Testcontainers schema: every other test in the suite that pins
        // `revtstmp` (see AsOfRevisionResolverConformanceTest) pins it into the FUTURE, never the
        // past, so these two seeded rows are the only ones in the whole live schema old enough to
        // fall on either side of a 30-day cutoff. The class is @Transactional with no
        // TestTransaction commit here, so every row this test inserts rolls back automatically and
        // is never visible to another test.
        long now = System.currentTimeMillis();
        long expiredTstmp = now - TimeUnit.DAYS.toMillis(400);
        long retainedTstmp = now - TimeUnit.DAYS.toMillis(5);

        Integer expiredRev = jdbcTemplate.queryForObject(
                "INSERT INTO revinfo (revtstmp) VALUES (?) RETURNING rev", Integer.class, expiredTstmp);
        Integer retainedRev = jdbcTemplate.queryForObject(
                "INSERT INTO revinfo (revtstmp) VALUES (?) RETURNING rev", Integer.class, retainedTstmp);

        UUID expiredDocumentId = UUID.randomUUID();
        UUID retainedDocumentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO document_audit (id, rev, revtype, title) VALUES (?, ?, 0, ?)",
                expiredDocumentId,
                expiredRev,
                "purge-test-expired");
        jdbcTemplate.update(
                "INSERT INTO document_audit (id, rev, revtype, title) VALUES (?, ?, 0, ?)",
                retainedDocumentId,
                retainedRev,
                "purge-test-retained");

        job.purgeOldAuditRecords();

        assertThat(jdbcTemplate.queryForList(
                        "SELECT 1 FROM document_audit WHERE id = ? AND rev = ?", expiredDocumentId, expiredRev))
                .as("expired document_audit row must be purged")
                .isEmpty();
        assertThat(jdbcTemplate.queryForList("SELECT 1 FROM revinfo WHERE rev = ?", expiredRev))
                .as("expired revinfo row must be purged")
                .isEmpty();
        assertThat(jdbcTemplate.queryForList(
                        "SELECT 1 FROM document_audit WHERE id = ? AND rev = ?", retainedDocumentId, retainedRev))
                .as("retained document_audit row must survive")
                .hasSize(1);
        assertThat(jdbcTemplate.queryForList("SELECT 1 FROM revinfo WHERE rev = ?", retainedRev))
                .as("retained revinfo row must survive")
                .hasSize(1);
    }
}
